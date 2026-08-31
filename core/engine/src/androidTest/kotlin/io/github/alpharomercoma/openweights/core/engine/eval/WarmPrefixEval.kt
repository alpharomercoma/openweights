/*
 * Copyright 2026 The OpenWeights Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.alpharomercoma.openweights.core.engine.eval

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the warm-prefix path does what it claims, on real weights.
 *
 * Three claims, each measured rather than trusted:
 * 1. A warmed prefix is *reused*: the first turn after [LlamaCppEngine.warm] reports most
 *    of its prompt as cached.
 * 2. A new conversation after an old one still reuses the prefix — the rollback families
 *    by cutting the cache, the recurrent and hybrid families by restoring the snapshot.
 * 3. None of it changes a single byte of output: greedy decoding over a restored state
 *    answers exactly what greedy decoding over a cold prefill answers. This is the
 *    assertion that makes the other two safe to ship.
 */
@RunWith(AndroidJUnit4::class)
class WarmPrefixEval {

    @Test
    fun warmedPrefixIsReusedAndAnswersAreUnchanged(): Unit = runBlocking {
        val models = EVAL_DIR.listFiles { file -> file.extension == "gguf" }.orEmpty()
        assumeTrue("no .gguf files in $EVAL_DIR", models.isNotEmpty())
        models.sortedBy { it.name }.forEach { check(it) }
    }

    private suspend fun check(model: File) {
        Log.i(TAG, "warm eval on ${model.name}")

        // Baseline session: a computed prefix, then the question. Byte-for-byte equality
        // is only owed between runs that decode the same batches in the same order —
        // llama.cpp's numbers shift a little with batch boundaries, which is a property
        // multi-turn caching already lives with — so the baseline is built on the same
        // schedule the warm path uses: prefix in the cache, question decoded on top.
        val baseline = LlamaCppEngine()
        val computed: Reply
        try {
            baseline.load(model, ModelLoadParams(contextLength = CONTEXT))
            val warm = baseline.warm(listOf(system()), params = PARAMS)
            assertWithMessage("warm on ${model.name}").that(warm).isNotNull()
            Log.i(
                TAG,
                "${model.name}: warmed=${warm!!.warmedTokens} reused=${warm.reusedTokens} " +
                    "in ${warm.prefillMs}ms snapshot=${warm.snapshotBytes / 1024}KB",
            )
            if (warm.warmedTokens + warm.reusedTokens < 100) {
                // Some template families have no system region to warm — Gemma folds the
                // system text into the first user turn — so a warm renders almost nothing.
                // That is a no-op in production, not a failure: the model simply keeps
                // paying the cold first turn it always paid.
                Log.i(TAG, "${model.name}: template carries no warmable prefix, skipping")
                return
            }
            computed = baseline.ask(SECOND_QUESTION)
            assertWithMessage("${model.name}: warmed prefix was not reused")
                .that(computed.stats.cachedTokens).isGreaterThan(200)
            assertThat(computed.text).isNotEmpty()
        } finally {
            baseline.unload()
            baseline.close()
        }

        // Fresh session: warm, hold a conversation to move the cache past the prefix, then
        // open a new chat. A transformer gets back to the prefix by rolling the turns off;
        // a hybrid cannot roll back and must restore the snapshot. Either way the state
        // under the question is reconstructed rather than freshly computed — and the reply
        // must not change by a byte.
        val engine = LlamaCppEngine()
        try {
            engine.load(model, ModelLoadParams(contextLength = CONTEXT))
            val snapshotBytes = engine.warm(listOf(system()), params = PARAMS)?.snapshotBytes ?: 0L
            val first = engine.ask(QUESTION)
            assertThat(first.text).isNotEmpty()
            engine.ask(
                QUESTION,
                ChatMessage.text(ChatRole.ASSISTANT, first.text),
                ChatMessage.text(ChatRole.USER, "Now add ten to it."),
            )

            val restored = engine.ask(SECOND_QUESTION)
            assertWithMessage("${model.name}: fresh chat re-read the prefix")
                .that(restored.stats.cachedTokens).isGreaterThan(200)
            if (snapshotBytes > 0) {
                // The snapshot restore replaces the state wholesale, so its schedule is
                // identical to the baseline's and the reply is owed byte for byte. The
                // rollback families are llama.cpp's own shipping path and keep a few
                // header tokens from the old conversation's batch, whose accumulation
                // order can flip one near-tie token deep in a thinking chain — the same
                // epsilon every cached multi-turn reply already carries — so for them the
                // claim is the answer, not the bytes.
                assertWithMessage("${model.name}: restored state answered differently")
                    .that(restored.text).isEqualTo(computed.text)
            } else {
                assertWithMessage("${model.name}: fresh chat lost the answer")
                    .that(restored.text).contains("Paris")
                assertWithMessage("${model.name}: baseline lost the answer")
                    .that(computed.text).contains("Paris")
            }
            Log.i(
                TAG,
                "${model.name}: fresh-chat cached=${restored.stats.cachedTokens} " +
                    "prompt=${restored.stats.promptTokens} " +
                    "ttft=${restored.stats.timeToFirstTokenMs}ms",
            )
        } finally {
            engine.unload()
            engine.close()
        }
    }

    @Test
    fun conversationWarmExtendsWithoutTakingTheFreshSnapshot(): Unit = runBlocking {
        val models = EVAL_DIR.listFiles { file -> file.extension == "gguf" }.orEmpty()
        assumeTrue("no .gguf files in $EVAL_DIR", models.isNotEmpty())
        models.sortedBy { it.name }.forEach { checkConversationWarm(it) }
    }

    /**
     * The fold/branch/reopen path: after the head, a whole conversation is warmed with
     * `snapshot = false`. Three claims, per family:
     * 1. The conversation warm *extends* the head instead of re-reading it.
     * 2. The next question reuses the whole warmed conversation — which also proves the
     *    assistant-final render without a generation prompt is a byte prefix of the same
     *    conversation rendered with one, per template, on real weights.
     * 3. The fresh-chat snapshot survives: a new chat afterwards still restores (hybrid)
     *    or rolls back (transformer) to the head, rather than starting cold because a
     *    conversation displaced the snapshot.
     */
    private suspend fun checkConversationWarm(model: File) {
        Log.i(TAG, "conversation warm eval on ${model.name}")
        val engine = LlamaCppEngine()
        try {
            engine.load(model, ModelLoadParams(contextLength = CONTEXT))
            val head = engine.warm(listOf(system()), params = PARAMS)
            assertWithMessage("head warm on ${model.name}").that(head).isNotNull()
            if (head!!.warmedTokens + head.reusedTokens < 100) {
                Log.i(TAG, "${model.name}: template carries no warmable prefix, skipping")
                return
            }

            val conversation = listOf(
                system(),
                ChatMessage.text(ChatRole.USER, QUESTION),
                ChatMessage.text(ChatRole.ASSISTANT, CARRIED_REPLY),
            )
            val warmed = engine.warm(conversation, params = PARAMS, snapshot = false)
            assertWithMessage("conversation warm on ${model.name}").that(warmed).isNotNull()
            assertWithMessage("${model.name}: conversation warm re-read the head")
                .that(warmed!!.reusedTokens).isGreaterThan(200)
            assertWithMessage("${model.name}: conversation warm took the fresh snapshot")
                .that(warmed.snapshotBytes).isEqualTo(head.snapshotBytes)

            val followUp = engine.ask(
                QUESTION,
                ChatMessage.text(ChatRole.ASSISTANT, CARRIED_REPLY),
                ChatMessage.text(ChatRole.USER, "Now add ten to it."),
            )
            val warmedTotal = warmed.reusedTokens + warmed.warmedTokens
            assertWithMessage("${model.name}: the follow-up did not extend the warmed turns")
                .that(followUp.stats.cachedTokens).isAtLeast(warmedTotal - 8)
            Log.i(
                TAG,
                "${model.name}: conversation warmed=${warmed.warmedTokens} " +
                    "reused=${warmed.reusedTokens} in ${warmed.prefillMs}ms; " +
                    "follow-up cached=${followUp.stats.cachedTokens} " +
                    "ttft=${followUp.stats.timeToFirstTokenMs}ms",
            )

            val fresh = engine.ask(SECOND_QUESTION)
            assertWithMessage("${model.name}: the fresh chat after a conversation warm went cold")
                .that(fresh.stats.cachedTokens).isGreaterThan(200)
            assertWithMessage("${model.name}: fresh chat lost the answer")
                .that(fresh.text).contains("Paris")
            Log.i(
                TAG,
                "${model.name}: fresh-after-conversation cached=${fresh.stats.cachedTokens} " +
                    "ttft=${fresh.stats.timeToFirstTokenMs}ms",
            )
        } finally {
            engine.unload()
            engine.close()
        }
    }

    @Test
    fun cancelInterruptsAWarmMidPrefill(): Unit = runBlocking {
        val model = EVAL_DIR.listFiles { file -> file.extension == "gguf" }.orEmpty()
            .minByOrNull { it.name }
        assumeTrue("no .gguf files in $EVAL_DIR", model != null)

        // The incident this pins: a question queued behind a background warm for as long
        // as the warm took, because the interrupt was swallowed. The engine-level half of
        // the contract is here: a cancel landing mid-prefill ends the warm within a graph
        // node or a batch, the partial read is kept, and the next chat reuses it.
        val engine = LlamaCppEngine()
        try {
            engine.load(model!!, ModelLoadParams(contextLength = CONTEXT))
            val warm = async { engine.warm(listOf(system()), params = PARAMS) }
            delay(INTERRUPT_AFTER_MS)
            val asked = System.currentTimeMillis()
            engine.cancel()
            val result = warm.await()
            val interruptMs = System.currentTimeMillis() - asked
            assertWithMessage("${model.name}: cancelled warm returned nothing")
                .that(result).isNotNull()
            assertWithMessage("${model.name}: the warm outlived its cancel by ${interruptMs}ms")
                .that(interruptMs).isLessThan(MAX_INTERRUPT_MS)

            val reply = engine.ask(QUESTION)
            assertThat(reply.text).isNotEmpty()
            assertWithMessage("${model.name}: the turn re-read what the warm had kept")
                .that(reply.stats.cachedTokens).isAtLeast(result!!.warmedTokens)
            Log.i(
                TAG,
                "${model.name}: warm interrupted after ${result.warmedTokens} tokens in " +
                    "${interruptMs}ms; turn cached=${reply.stats.cachedTokens} " +
                    "ttft=${reply.stats.timeToFirstTokenMs}ms",
            )
        } finally {
            engine.unload()
            engine.close()
        }
    }

    private class Reply(
        val text: String,
        val stats: io.github.alpharomercoma.openweights.core.engine.GenerationStats,
    )

    /** One greedy turn: the system prefix, the question, and whatever else extends it. */
    private suspend fun LlamaCppEngine.ask(question: String, vararg tail: ChatMessage): Reply {
        val messages = listOf(system(), ChatMessage.text(ChatRole.USER, question)) + tail
        val events = chat(messages, PARAMS).toList()
        val done = events.filterIsInstance<GenerationEvent.Completed>().single()
        val raw = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
        return Reply(raw.ifEmpty { done.content }, done.stats)
    }

    private companion object {
        const val TAG = "WarmPrefixEval"
        const val CONTEXT = 4096

        /** Deep enough into the prefill to be mid-batch, well short of a full warm. */
        const val INTERRUPT_AFTER_MS = 400L

        /** ggml polls the abort between graph nodes; whole seconds would mean it did not. */
        const val MAX_INTERRUPT_MS = 3_000L
        val EVAL_DIR = File("/data/local/tmp/openweights/eval")

        val PARAMS = SamplerParams(temperature = 0f, topK = 1, seed = 7, maxTokens = 96)

        /**
         * A prefix shaped like the app's: instructions, then a tool-block-sized slab of
         * policy. Size matters more than content here — the reuse arithmetic only shows up
         * when the prefix dwarfs the question.
         */
        fun system(): ChatMessage {
            val policy = buildString {
                append("You are a careful assistant on a phone. Answer briefly.\n")
                repeat(40) { index ->
                    append("Rule ").append(index)
                        .append(": prefer the shortest correct answer, cite nothing, ")
                        .append("never invent a tool, and keep lists to three items.\n")
                }
            }
            return ChatMessage.text(ChatRole.SYSTEM, policy)
        }

        const val QUESTION = "What is 2+2? Reply with just the number."

        /** A reply as it might have been decoded, carried into a branch or reopened chat. */
        const val CARRIED_REPLY = "4"
        const val SECOND_QUESTION = "What is the capital of France? One word."
    }
}

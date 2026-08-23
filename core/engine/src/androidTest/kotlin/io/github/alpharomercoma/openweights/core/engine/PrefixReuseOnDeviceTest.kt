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

package io.github.alpharomercoma.openweights.core.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.assistantHistoryText
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether a second turn pays for the first turn again.
 *
 * The engine reuses whatever prefix of the prompt is already in the KV cache, which is the
 * single largest thing standing between this app and a usable wait: the system message plus
 * eight tool definitions is around 1,200 tokens, and on a Snapdragon 8 Gen 3 that is about
 * 8.6 seconds of prompt processing at the rate measured there. If the reuse works, a
 * follow-up costs only the new question. If it does not, every turn pays the 8.6 seconds
 * again and nothing in the UI would say so.
 *
 * There is a specific reason to doubt it for the models this app recommends.
 * `Session::generate` rolls the cache back with `llama_memory_seq_rm` whenever the new
 * prompt is not a strict extension of the old one, and a hybrid model like LFM2 carries a
 * running convolution state rather than a row per token, so it **refuses** the rollback and
 * the engine correctly starts over. Anything that makes the re-rendered history differ from
 * what was decoded, by even one token, therefore costs a full prefill rather than a partial
 * one. Reasoning is the obvious candidate: it is generated, and whether it comes back in
 * the next prompt is the chat template's decision, not ours.
 *
 * So this measures three shapes over one loaded model:
 *
 *  1. a plain follow-up, no reasoning in the reply
 *  2. a follow-up where the stored reply carries its `<think>` block, which is what
 *     `canonicalText` in ChatViewModel writes down
 *  3. a follow-up where the reply was stored with the thinking stripped
 *
 * and prints what each one had to re-read.
 */
@RunWith(AndroidJUnit4::class)
class PrefixReuseOnDeviceTest {
    private val modelDir = File("/data/local/tmp/openweights")

    @Test
    fun aFollowUpTurnOnlyPaysForWhatIsNew() = runBlocking {
        val model = modelDir.listFiles { file -> file.name.endsWith(".gguf") }
            ?.filterNot { it.name.contains("mmproj") }
            ?.sortedBy { it.name }
            ?.firstOrNull()
        assumeTrue("no .gguf in $modelDir", model != null)
        requireNotNull(model)

        LlamaCppEngine().use { engine ->
            engine.load(model, ModelLoadParams(contextLength = CONTEXT))

            val system = ChatMessage.text(ChatRole.SYSTEM, systemPrompt())
            val first = listOf(system, ChatMessage.text(ChatRole.USER, FIRST_QUESTION))
            val opening = engine.turn(first)
            Log.i(TAG, "${model.name} turn 1: ${describe(opening)}")
            assertThat(opening.stats.promptTokens).isGreaterThan(MEANINGFUL_PROMPT)

            // Exactly what ChatViewModel now sends back: the reply untouched, with the
            // opening tag its own template pre-filled put back in front of it.
            val canonical = assistantHistoryText(opening.content)

            val withThinking = engine.turn(
                first + ChatMessage.text(ChatRole.ASSISTANT, canonical) +
                    ChatMessage.text(ChatRole.USER, SECOND_QUESTION),
            )
            Log.i(TAG, "turn 2, reply stored whole: ${describe(withThinking)}")

            // The same follow-up with the reply put through the parser first, which is
            // what the transcript shows and stores and what used to be sent. Kept as the
            // control: it is the version that costs a whole prefill.
            engine.resetContext()
            engine.turn(first)
            val parsed = parseAssistantReply(opening.content)
            val displayed = if (parsed.reasoning.isNullOrEmpty()) {
                parsed.answer
            } else {
                "<think>${parsed.reasoning}</think>${parsed.answer}"
            }
            val withoutThinking = engine.turn(
                first + ChatMessage.text(ChatRole.ASSISTANT, displayed) +
                    ChatMessage.text(ChatRole.USER, SECOND_QUESTION),
            )
            Log.i(TAG, "turn 2, reply put through the parser: ${describe(withoutThinking)}")

            Log.i(
                TAG,
                "reuse: untouched=${withThinking.stats.promptTokens} " +
                    "parsed=${withoutThinking.stats.promptTokens} " +
                    "against a first turn of ${opening.stats.promptTokens}",
            )

            // The claim being tested. A follow-up re-reads the new question and the reply
            // it is answering, not the whole conversation, so it must come in far under
            // what the opening turn paid.
            assertThat(withThinking.stats.promptTokens)
                .isLessThan(opening.stats.promptTokens / 8)
        }
    }

    private suspend fun InferenceEngine.turn(messages: List<ChatMessage>) = chat(
        messages = messages,
        params = SamplerParams(temperature = 0f, maxTokens = ANSWER_TOKENS, seed = 1),
    ).toList().filterIsInstance<GenerationEvent.Completed>().single()

    private fun describe(event: GenerationEvent.Completed): String =
        "prompt=%d tokens prefill=%dms ttft=%dms generated=%d context=%d".format(
            event.stats.promptTokens,
            event.stats.prefillMs,
            event.stats.timeToFirstTokenMs,
            event.stats.generatedTokens,
            event.stats.contextUsed,
        )

    /**
     * A system message the size of the real one.
     *
     * The point of the test is what a large constant prefix costs on the second turn, so it
     * has to be large. The tool definitions the app sends are around a thousand tokens of
     * JSON; this stands in for them at the same order of magnitude without importing
     * core:tools into an engine test.
     */
    private fun systemPrompt(): String = buildString {
        append("Today is 2026-08-23.\n\nAnswer from what you know, in a few sentences.\n\n")
        repeat(STANZAS) { index ->
            append("Note ").append(index + 1).append(". Answer from your own knowledge ")
            append("whenever you can, and say plainly when you cannot. Keep replies short ")
            append("and put the answer first. Do not repeat the question back. Where a ")
            append("number is asked for, give the number. Where a comparison is asked ")
            append("for, give both sides of it.\n")
        }
    }

    private companion object {
        const val TAG = "OpenWeights"
        const val CONTEXT = 4096

        /**
         * Enough for the model to finish thinking and then answer.
         *
         * Not a round number picked for speed. At 48 the 2.6B spends the whole budget
         * inside its thinking block, never emits `</think>`, and the reply comes back as
         * unseparated prose, which is a different case from the one being measured.
         */
        const val ANSWER_TOKENS = 256
        const val STANZAS = 18
        const val FIRST_QUESTION = "What is the capital of Japan?"
        const val SECOND_QUESTION = "And the capital of France?"

        /** Enough of a prompt that an eighth of it is a measurement rather than rounding. */
        const val MEANINGFUL_PROMPT = 400
    }
}

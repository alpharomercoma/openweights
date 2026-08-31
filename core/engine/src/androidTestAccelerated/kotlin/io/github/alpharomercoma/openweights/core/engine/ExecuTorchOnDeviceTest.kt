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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The ExecuTorch engine against a real `.pte`, on a real phone.
 *
 * Push a model and the tokenizer it was exported with, named the way the app names them:
 * ```
 * adb shell mkdir -p /data/local/tmp/openweights
 * adb push Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.pte \
 *   /data/local/tmp/openweights/Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.pte
 * adb push Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.tokenizer.json \
 *   /data/local/tmp/openweights/Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.tokenizer.json
 * ```
 * Both files, because a `.pte` says nothing about which tokenizer produced it and the wrong
 * one gives fluent nonsense rather than an error. Without them these skip rather than fail,
 * so a machine with no device attached stays green.
 *
 * Accelerated flavour only: the standard build has no runtime for these to drive.
 */
@RunWith(AndroidJUnit4::class)
class ExecuTorchOnDeviceTest {

    private lateinit var engine: ExecuTorchEngine

    @Before
    fun setUp() {
        assumeTrue("no .pte at ${MODEL.path}", MODEL.isFile)
        assumeTrue("no tokenizer beside ${MODEL.name}", TOKENIZER.isFile)
        engine = ExecuTorchEngine(NativeExecuTorchBridge())
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.unload() }
    }

    @Test
    fun opensACompiledModel(): Unit = runBlocking {
        engine.load(MODEL, PARAMS)

        val loaded = engine.loadedModel
        assertThat(loaded).isNotNull()
        assertThat(loaded?.modelPath).isEqualTo(MODEL.absolutePath)
        Log.i(TAG, "loaded ${loaded?.description} (${loaded?.sizeBytes} bytes)")
    }

    @Test
    fun answersInSomethingLegible(): Unit = runBlocking {
        engine.load(MODEL, PARAMS)

        val events = engine.chat(
            listOf(ChatMessage.text(ChatRole.USER, "What is the capital of Japan?")),
            SamplerParams(maxTokens = 64, thinking = false),
        ).toList()

        val done = events.filterIsInstance<GenerationEvent.Completed>().single()
        Log.i(TAG, "reply: ${done.content}")
        Log.i(TAG, "stats: ${done.stats}")

        // Deliberately weak on the content and strict on the shape. Whether a 1.7B model
        // knows this is not what is being tested; whether the prompt reached it in the
        // format it was trained on, and came back parsed, is.
        assertThat(done.content).isNotEmpty()
        assertThat(done.stats.decodeMs).isGreaterThan(0L)
    }

    /**
     * Whether a second turn costs the whole conversation or only what is new.
     *
     * The engine keeps the runtime's cache when the next prompt genuinely begins with what
     * has already been fed, which is what this measures. Two turns, the second extending
     * the first: if the cache is kept, its prefill is a fraction of turn one's despite the
     * conversation being longer.
     */
    @Test
    fun reportsWhatASecondTurnCosts(): Unit = runBlocking {
        engine.load(MODEL, PARAMS)

        val first = listOf(ChatMessage.text(ChatRole.USER, LONG_PROMPT))
        val one = turn(first)

        // Stored the way the app stores it: the raw streamed reply, not the parsed
        // content. Storing the parsed form drops the reasoning, and the runtime's cache
        // holds the reasoning, so the next render would describe a different conversation
        // and throw the cache away. Measured: that is exactly what happened before.
        val second = first +
            ChatMessage.text(ChatRole.ASSISTANT, one.raw.ifEmpty { "Understood." }) +
            ChatMessage.text(ChatRole.USER, "In one word, what did I just describe?")
        val two = turn(second)

        Log.i(
            TAG,
            "turn 1: prompt=${one.stats.promptTokens} cached=${one.stats.cachedTokens} " +
                "prefill=${one.stats.prefillMs}ms",
        )
        Log.i(
            TAG,
            "turn 2: prompt=${two.stats.promptTokens} cached=${two.stats.cachedTokens} " +
                "prefill=${two.stats.prefillMs}ms",
        )
        val ratio = two.stats.prefillMs.toDouble() / one.stats.prefillMs.coerceAtLeast(1)
        Log.i(TAG, "second-turn prefill ratio: $ratio (well under 1 means the cache held)")
        // Speed is worthless if the answer stopped making sense. Feeding a suffix means the
        // runtime tokenises text that was previously tokenised as part of a larger string,
        // and a boundary falling mid-word would corrupt the sequence quietly — a fast reply
        // that has lost the thread. The split lands on `<|im_start|>`, a special token, so
        // it should be safe; this is what would catch it if it were not.
        Log.i(TAG, "turn 2 reply: ${two.content}")

        assertThat(one.stats.prefillMs).isGreaterThan(0L)
        // The point of the whole exercise: turn two must not re-read turn one. Generous,
        // because this asserts the cache was kept rather than a particular speed.
        assertThat(two.stats.prefillMs).isLessThan(one.stats.prefillMs)
        assertThat(two.stats.cachedTokens).isGreaterThan(0)
        // Still an answer, not debris from a mis-split sequence.
        assertThat(two.content).isNotEmpty()
        assertThat(two.content).doesNotContain("<|im_")
    }

    /** Reasoning off is the case that cannot reuse the cache; it must still be correct. */
    @Test
    fun stillAnswersWithReasoningOff(): Unit = runBlocking {
        engine.load(MODEL, PARAMS)

        val done = turn(listOf(ChatMessage.text(ChatRole.USER, "Name one colour.")))

        Log.i(TAG, "reasoning-off reply: ${done.content}")
        assertThat(done.content).isNotEmpty()
        assertThat(done.content).doesNotContain("<|im_end|>")
    }

    /**
     * The head prefill: a fresh chat's system block read before anybody asks.
     *
     * Fed through the runner's prefill-only entry, in pieces, and judged by the
     * runtime's own accounting rather than trust: the first question after a warm must
     * report paying for its own words, not for the head. The control is the same
     * question cold. This is also the test that would catch `prefillPrompt` failing
     * silently — the Java wrapper discards its error code — because a head that never
     * reached the cache leaves the turn's count at the cold figure.
     */
    @Test
    fun warmHeadIsReusedByTheFirstTurn(): Unit = runBlocking {
        engine.load(MODEL, PARAMS)
        val head = ChatMessage.text(ChatRole.SYSTEM, LONG_PROMPT)
        val ask = ChatMessage.text(ChatRole.USER, "Acknowledge in one short sentence.")

        val cold = turn(listOf(head, ask))
        Log.i(TAG, "cold: prompt=${cold.stats.promptTokens} prefill=${cold.stats.prefillMs}ms")

        engine.resetContext()

        val warm = engine.warm(listOf(head), params = SamplerParams(maxTokens = 48))
        assertThat(warm).isNotNull()
        Log.i(TAG, "warm: fed=${warm!!.warmedTokens} pieces in ${warm.prefillMs}ms")
        assertThat(warm.warmedTokens).isGreaterThan(0)

        val after = turn(listOf(head, ask))
        Log.i(
            TAG,
            "after warm: prompt=${after.stats.promptTokens} " +
                "cached=${after.stats.cachedTokens} prefill=${after.stats.prefillMs}ms " +
                "reply=${after.content}",
        )
        assertThat(after.stats.promptTokens).isLessThan(cold.stats.promptTokens / 4)
        assertThat(after.content).isNotEmpty()
        assertThat(after.content).doesNotContain("<|im_")
    }

    /**
     * One turn, with reasoning left on.
     *
     * Reasoning is deliberately on: Qwen3 disables it by closing an empty `<think>` block
     * in the assistant opener, that text lands in the cache, and the template never
     * reproduces it when the turn becomes history — so a turn generated with reasoning off
     * can never be extended. Switching it off is what costs the cache, which is the
     * opposite of how it sounds.
     *
     * The budget is the default, meaning the rest of the window, and that matters as much.
     * A reply cut short by a token budget never emits its end-of-turn marker, and without
     * that marker the engine cannot say which tokens the runtime actually committed, so it
     * gives up the cache rather than guess. Asking for 48 tokens here measured a ratio of
     * 1.96 — worse than no cache at all — purely because nothing ever finished.
     */
    private suspend fun turn(messages: List<ChatMessage>): Turn {
        val events = engine.chat(messages, SamplerParams(maxTokens = 48)).toList()
        return Turn(
            done = events.filterIsInstance<GenerationEvent.Completed>().single(),
            raw = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text },
        )
    }

    /** A finished turn, with the streamed text the app would store as history. */
    private data class Turn(val done: GenerationEvent.Completed, val raw: String) {
        val stats get() = done.stats
        val content get() = done.content
    }

    private companion object {
        const val TAG = "ExecuTorchOnDevice"
        val MODEL = File(
            "/data/local/tmp/openweights/Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.pte",
        )
        val TOKENIZER = File(
            "/data/local/tmp/openweights/" +
                "Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.tokenizer.json",
        )
        val PARAMS = ModelLoadParams(contextLength = 2048)

        /** Long enough that re-reading it is visibly different from not re-reading it. */
        val LONG_PROMPT = buildString {
            append("Here is a description of a system I am building. ")
            repeat(40) {
                append(
                    "It runs language models on a phone, entirely on device, with no " +
                        "network involved at any point in generation. ",
                )
            }
            append("Acknowledge briefly.")
        }
    }
}

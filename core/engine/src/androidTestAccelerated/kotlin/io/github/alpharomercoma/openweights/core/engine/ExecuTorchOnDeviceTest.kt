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
     * Whether a second turn costs what the whole conversation costs, or only what is new.
     *
     * This is the open question that decides whether this engine is usable for the app's
     * real traffic. `LlmModule` carries state — it has `resetContext` and a
     * prefill-without-generating call — but nothing in its signatures says whether an
     * ordinary generation continues from it, and the app's multi-turn cost is dominated by
     * the answer. See docs/research/executorch.md.
     *
     * Measured rather than asserted, because the honest form of this test is a number in a
     * log, not a threshold somebody guessed. Assertions here cover only that both turns ran.
     */
    @Test
    fun reportsWhatASecondTurnCosts(): Unit = runBlocking {
        engine.load(MODEL, PARAMS)

        val first = listOf(ChatMessage.text(ChatRole.USER, LONG_PROMPT))
        val one = turn(first)

        val second = first +
            ChatMessage.text(ChatRole.ASSISTANT, one.content.ifEmpty { "Understood." }) +
            ChatMessage.text(ChatRole.USER, "In one word, what did I just describe?")
        val two = turn(second)

        Log.i(
            TAG,
            "turn 1: prompt=${one.stats.promptTokens} prefill=${one.stats.prefillMs}ms " +
                "ttft=${one.stats.timeToFirstTokenMs}ms",
        )
        Log.i(
            TAG,
            "turn 2: prompt=${two.stats.promptTokens} prefill=${two.stats.prefillMs}ms " +
                "ttft=${two.stats.timeToFirstTokenMs}ms",
        )
        // The number this test exists to produce. Well under 1 means the second turn only
        // paid for what was new, and prefix reuse is real; at or above 1 means the whole
        // conversation was re-read and the engine re-prefills every turn.
        val ratio = two.stats.prefillMs.toDouble() / one.stats.prefillMs.coerceAtLeast(1)
        Log.i(TAG, "second-turn prefill ratio: $ratio (prompt grew, so <1 means reuse)")

        assertThat(one.stats.prefillMs).isGreaterThan(0L)
        assertThat(two.stats.prefillMs).isGreaterThan(0L)
    }

    private suspend fun turn(messages: List<ChatMessage>): GenerationEvent.Completed =
        engine.chat(messages, SamplerParams(maxTokens = 24, thinking = false))
            .toList()
            .filterIsInstance<GenerationEvent.Completed>()
            .single()

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

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
 * Exercises the real native engine against a real GGUF on a real device.
 *
 * The model is not committed: push one first:
 * ```
 * adb shell mkdir -p /data/local/tmp/openweights
 * adb push LFM2.5-2.6B-Q4_K_M.gguf /data/local/tmp/openweights/model.gguf
 * ```
 * Without it, these tests skip rather than fail, so CI stays green on machines with no
 * device attached.
 */
@RunWith(AndroidJUnit4::class)
class LlamaCppEngineTest {
    private lateinit var engine: InferenceEngine

    @Before
    fun setUp() {
        assumeTrue("no test model at ${MODEL.path}", MODEL.isFile)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) {
            runBlocking { engine.unload() }
        }
    }

    @Test
    fun reportsBackendsAndCpuFeatures() {
        val info = engine.systemInfo()
        Log.i(TAG, "system: $info")
        assertThat(info).isNotEmpty()
    }

    @Test
    fun loadsModelAndReportsItsShape() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT_LENGTH))

        val model = requireNotNull(engine.loadedModel)
        Log.i(
            TAG,
            "model: ${model.description} | ${model.parameterCount} params | " +
                "${model.layerCount} layers | trained to ${model.trainingContextSize} tokens",
        )
        assertThat(model.parameterCount).isGreaterThan(0L)
        assertThat(model.layerCount).isGreaterThan(0)
        assertThat(model.contextSize).isEqualTo(CONTEXT_LENGTH)
    }

    @Test
    fun generatesAReplyAndMeasuresThroughput() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT_LENGTH))

        val events = engine.chat(
            messages = listOf(
                ChatMessage.text(ChatRole.USER, "In one short sentence: what is a KV cache?"),
            ),
            params = SamplerParams(temperature = 0.2f, maxTokens = 96, seed = 42),
        ).toList()

        val reply = events.filterIsInstance<GenerationEvent.Token>().joinToString("") {
            it.text
        }
        val completed = events.filterIsInstance<GenerationEvent.Completed>().single()

        Log.i(TAG, "reply: $reply")
        Log.i(
            TAG,
            "MEASURED prefill=%.1f tok/s decode=%.1f tok/s ttft=%d ms prompt=%d gen=%d stop=%s"
                .format(
                    completed.stats.prefillTokensPerSecond ?: 0.0,
                    completed.stats.decodeTokensPerSecond ?: 0.0,
                    completed.stats.timeToFirstTokenMs,
                    completed.stats.promptTokens,
                    completed.stats.generatedTokens,
                    completed.reason,
                ),
        )

        assertThat(reply.trim()).isNotEmpty()
        assertThat(completed.stats.generatedTokens).isGreaterThan(0)
        assertThat(completed.reason).isAnyOf(StopReason.END_OF_TURN, StopReason.MAX_TOKENS)
    }

    /**
     * Re-sending an identical conversation must decode exactly one prompt token: everything
     * before it is already in the KV cache. One token always has to be decoded to produce
     * logits to sample from.
     */
    @Test
    fun reusesCachedPrefixWhenThePromptRepeats() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT_LENGTH))
        val params = SamplerParams(temperature = 0.2f, maxTokens = 8, seed = 7)
        val conversation = listOf(
            ChatMessage.text(ChatRole.USER, "Name one colour. Answer with one word."),
        )

        val first = engine.chat(conversation, params).toList()
            .filterIsInstance<GenerationEvent.Completed>().single()
        val second = engine.chat(conversation, params).toList()
            .filterIsInstance<GenerationEvent.Completed>().single()

        Log.i(
            TAG,
            "cold prompt decoded ${first.stats.promptTokens} tokens, " +
                "repeat decoded ${second.stats.promptTokens}",
        )
        assertThat(first.stats.promptTokens).isGreaterThan(1)
        assertThat(second.stats.promptTokens).isEqualTo(1)
    }

    /** A follow-up turn should only pay for the tokens the previous turn did not cover. */
    @Test
    fun reusesCachedPrefixOnFollowUpTurns() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT_LENGTH))
        val params = SamplerParams(temperature = 0.2f, maxTokens = 8, seed = 7)
        val question = ChatMessage.text(ChatRole.USER, "Name one colour. Answer with one word.")

        val first = engine.chat(listOf(question), params).toList()
            .filterIsInstance<GenerationEvent.Completed>().single()

        val followUp = listOf(
            question,
            ChatMessage.text(ChatRole.ASSISTANT, "Blue."),
            ChatMessage.text(ChatRole.USER, "Name another one."),
        )
        val second = engine.chat(followUp, params).toList()
            .filterIsInstance<GenerationEvent.Completed>().single()

        Log.i(
            TAG,
            "turn 1 decoded ${first.stats.promptTokens} prompt tokens, " +
                "turn 2 decoded ${second.stats.promptTokens}",
        )
        // Turn 2's prompt is turn 1's prompt plus two more messages. Without reuse it would
        // decode all of it; with reuse it only decodes the added tail.
        assertThat(second.stats.promptTokens).isLessThan(first.stats.promptTokens)
    }

    private companion object {
        const val TAG = "OpenWeightsTest"
        const val CONTEXT_LENGTH = 2048
        val MODEL = File("/data/local/tmp/openweights/model.gguf")
    }
}

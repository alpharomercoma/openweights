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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What offloading to the GPU is worth on a turn shaped like the ones this app produces.
 *
 * The published figures for this chip come from llama-bench, which measures reading and
 * writing separately. A turn does both, in a ratio that depends entirely on what the user
 * asked for: a chat turn reads a short question and writes a long answer, and a turn that
 * searched reads a conversation plus a page of results and writes a paragraph. The second
 * is the one every published benchmark leaves out and the one this app is for.
 *
 * So both are measured here, through the engine that ships, and the numbers go in
 * docs/CONTEXT.md. Nothing is asserted about which wins: this exists to produce a table.
 */
@RunWith(AndroidJUnit4::class)
class OffloadBenchmark {
    private val modelFile = File("/data/local/tmp/openweights/model.gguf")

    @Test
    fun measuresBothShapesOfTurnOnCpuAndGpu() = runBlocking {
        assumeTrue("model.gguf not pushed", modelFile.exists())
        val gpuPresent = LlamaCppEngine().use { engine ->
            engine.computeDevices().any { it.kind == ComputeDeviceKind.GPU }
        }
        assumeTrue("no GPU backend on this device", gpuPresent)

        for (layers in listOf(0, ALL_LAYERS)) {
            val where = if (layers == 0) "cpu" else "gpu"
            LlamaCppEngine().use { engine ->
                engine.load(modelFile, ModelLoadParams(contextLength = CONTEXT, gpuLayers = layers))

                // A chat turn: short question, long answer.
                report(where, "chat", engine.turn("Explain what a KV cache is.", LONG_ANSWER))
                engine.resetContext()

                // An agent turn: a conversation and a page of search results to re-read,
                // then a paragraph. The prompt is what a second round actually looks like.
                report(where, "agent", engine.turn(agentPrompt(), SHORT_ANSWER))
            }
        }
    }

    private suspend fun InferenceEngine.turn(prompt: String, maxTokens: Int): GenerationStats {
        val completed = chat(
            messages = listOf(ChatMessage.text(ChatRole.USER, prompt)),
            params = SamplerParams(temperature = 0f, maxTokens = maxTokens, seed = 1),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()
        assertThat(completed.stats.promptTokens).isGreaterThan(0)
        return completed.stats
    }

    private fun report(where: String, shape: String, stats: GenerationStats) {
        val wall = stats.prefillMs + stats.decodeMs
        Log.i(
            TAG,
            "%s %s: prompt=%d gen=%d prefill=%dms decode=%dms wall=%dms %.1f pp/s %.1f tg/s"
                .format(
                    where,
                    shape,
                    stats.promptTokens,
                    stats.generatedTokens,
                    stats.prefillMs,
                    stats.decodeMs,
                    wall,
                    stats.promptTokens * MILLIS / stats.prefillMs.coerceAtLeast(1).toDouble(),
                    stats.generatedTokens * MILLIS / stats.decodeMs.coerceAtLeast(1).toDouble(),
                ),
        )
    }

    /** A conversation plus search results, which is what the second pass of a tool turn reads. */
    private fun agentPrompt(): String = buildString {
        append("Here is what the search returned. Answer the question from it.\n\n")
        repeat(RESULT_BLOCKS) { index ->
            append("[").append(index + 1).append("] Result ").append(index + 1).append('\n')
            append(
                "Ada Lovelace worked with Charles Babbage on the Analytical Engine and is " +
                    "credited with the first published algorithm intended for a machine. " +
                    "She saw that such a machine might act on things other than number. ",
            )
            append('\n')
        }
        append("\nQuestion: who was Ada Lovelace, in two sentences?")
    }

    private companion object {
        const val TAG = "OpenWeightsOffload"
        const val CONTEXT = 4096
        const val ALL_LAYERS = 99
        const val LONG_ANSWER = 300
        const val SHORT_ANSWER = 120
        const val MILLIS = 1000.0

        /** Enough blocks to land near two thousand tokens, which is a real second pass. */
        const val RESULT_BLOCKS = 40
    }
}

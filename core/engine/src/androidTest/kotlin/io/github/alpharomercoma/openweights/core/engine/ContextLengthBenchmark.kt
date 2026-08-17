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
 * What asking for a wider context window actually costs.
 *
 * The app ships one number, 4096, for every model on every phone, and it is neither the
 * largest a device could hold nor anything to do with what the model was trained for. The
 * argument for raising it is obvious and the argument against it is not, which is what this
 * measures: llama.cpp allocates the whole KV cache when the weights are mapped, so a window
 * is paid for in memory the moment it is asked for, whether or not a conversation ever
 * reaches it.
 *
 * Three questions, and the third is the one that decides the default:
 *
 * 1. What does the memory cost, per thousand tokens of window, for this model?
 * 2. What does the load cost, since the allocation happens there?
 * 3. Does a wide but empty window slow decoding down? If it does, a large default is wrong
 *    even on a phone with the memory for it, because most turns never fill it.
 *
 * Resident set is read from `/proc/self/status` rather than from the Java heap, because
 * nothing here is on the Java heap: the weights are mapped and the cache is a native
 * allocation, and `Debug.getNativeHeapAllocatedSize` does not see mmap either.
 */
@RunWith(AndroidJUnit4::class)
class ContextLengthBenchmark {
    private val modelFile = File("/data/local/tmp/openweights/model.gguf")

    @Test
    fun measuresWhatAWiderWindowCosts() = runBlocking {
        assumeTrue("model.gguf not pushed", modelFile.exists())
        Log.i(TAG, "model ${modelFile.length() / MIB} MiB, baseline rss ${residentMiB()} MiB")

        for (context in WINDOWS) {
            val outcome = runCatching { measure(context) }
            outcome.onFailure {
                // A refusal is the answer for that width, not a broken run: it is the point
                // at which this device cannot hold the cache, which is exactly what a
                // default has to stay under.
                Log.i(TAG, "ctx=$context refused: ${it.message}")
            }
        }
    }

    /**
     * What a window costs once it is actually full, which the sweep above does not say.
     *
     * The sweep loads six widths and measures a short turn in each, so every reading is of an
     * almost empty cache. That answers "does reserving a wide window cost anything", and the
     * answer is no. It does not answer the question that decides whether a wide default is
     * safe, which is what happens when somebody fills it: attention reads the whole cache on
     * every token, so decode should fall as the conversation grows whatever the window was
     * declared as.
     *
     * One load, one growing conversation, decode measured as it goes.
     */
    @Test
    fun measuresWhatAFullWindowCosts() = runBlocking {
        assumeTrue("model.gguf not pushed", modelFile.exists())
        LlamaCppEngine().use { engine ->
            engine.load(modelFile, ModelLoadParams(contextLength = WIDE, gpuLayers = 0))
            engine.turn("Say hello.", WARM_UP_TOKENS)

            for (fill in FILLS) {
                engine.resetContext()
                val stats = engine.turn(filler(fill), ANSWER_TOKENS)
                Log.i(
                    TAG,
                    "fill≈%d prompt=%d prefill=%dms %.1f tg/s".format(
                        fill,
                        stats.promptTokens,
                        stats.prefillMs,
                        stats.generatedTokens * MILLIS /
                            stats.decodeMs.coerceAtLeast(1).toDouble(),
                    ),
                )
            }
        }
    }

    /** Roughly [tokens] tokens of ordinary prose, since that is what a conversation is. */
    private fun filler(tokens: Int): String = buildString {
        append("Here are some notes. Answer the question at the end in one sentence.\n")
        repeat(tokens / WORDS_PER_BLOCK) {
            append("Ada Lovelace worked with Charles Babbage on the Analytical Engine. ")
        }
        append("\nQuestion: who was Ada Lovelace?")
    }

    private suspend fun measure(context: Int) {
        LlamaCppEngine().use { engine ->
            val before = residentMiB()
            val startedAt = System.currentTimeMillis()
            engine.load(modelFile, ModelLoadParams(contextLength = context, gpuLayers = 0))
            val loadMs = System.currentTimeMillis() - startedAt
            val loaded = residentMiB()

            // Warm, so the figure is throughput rather than first-call setup, and short, so
            // the window under test stays almost entirely empty. That is the case a default
            // has to be right for: a wide window nobody has filled yet.
            engine.turn("Say hello.", WARM_UP_TOKENS)
            engine.resetContext()
            val stats = engine.turn("Explain what a KV cache is.", ANSWER_TOKENS)

            Log.i(
                TAG,
                "ctx=%d load=%dms rss=%d MiB (+%d for the window) %.1f pp/s %.1f tg/s".format(
                    context,
                    loadMs,
                    loaded,
                    loaded - before,
                    stats.promptTokens * MILLIS / stats.prefillMs.coerceAtLeast(1).toDouble(),
                    stats.generatedTokens * MILLIS / stats.decodeMs.coerceAtLeast(1).toDouble(),
                ),
            )
            assertThat(stats.generatedTokens).isGreaterThan(0)
        }
    }

    private suspend fun InferenceEngine.turn(prompt: String, maxTokens: Int): GenerationStats =
        chat(
            messages = listOf(ChatMessage.text(ChatRole.USER, prompt)),
            params = SamplerParams(temperature = 0f, maxTokens = maxTokens, seed = 1),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single().stats

    /** This process's resident set, which is what the platform kills on. */
    private fun residentMiB(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmRSS:") }
        ?.filter { it.isDigit() }
        ?.toLongOrNull()
        ?.div(KIB_PER_MIB)
        ?: 0

    private companion object {
        const val TAG = "OpenWeightsContext"
        const val MIB = 1024L * 1024
        const val KIB_PER_MIB = 1024L
        const val MILLIS = 1000.0
        const val WARM_UP_TOKENS = 8
        const val ANSWER_TOKENS = 96

        /**
         * Doubling from what ships to past what any phone can hold.
         *
         * The refusals at the top are as much the result as the timings below them: the
         * default has to be the largest width that loads, and that is a different number on
         * every device and every model.
         */
        val WINDOWS = listOf(4_096, 8_192, 16_384, 32_768, 65_536, 131_072)

        /** Wide enough that the fills below are the variable rather than the ceiling. */
        const val WIDE = 32_768

        /** How full to make the cache before measuring, in tokens. */
        val FILLS = listOf(0, 1_024, 4_096, 8_192, 16_384)

        /** The filler block is about twelve tokens, so this is close enough to aim with. */
        const val WORDS_PER_BLOCK = 12
    }
}

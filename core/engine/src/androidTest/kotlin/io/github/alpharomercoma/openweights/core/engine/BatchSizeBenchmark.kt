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
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What a bigger prompt batch buys, and what it costs in memory to buy it.
 *
 * `n_batch` and `n_ubatch` were hard-coded at 512 with a comment reasoning that it "keeps
 * memory modest on phones while still batching enough work to be fast". Reasoning is not a
 * measurement, and this is the last engine constant in the app that had never been swept.
 * It matters because prefill is the compute-bound half of a turn and the half a person
 * waits through: the app's own first-turn work put roughly two thousand tokens of prefix in
 * front of every new conversation.
 *
 * The two are different things and the sweep keeps them apart. `n_batch` is how many prompt
 * tokens are handed to the engine at once; `n_ubatch` is how many are computed in one
 * graph, and it is the one that sizes the scratch buffer for intermediate activations. So
 * raising the logical batch alone changes how work is queued, and raising the physical
 * batch changes how much memory the phone needs while it is being done.
 *
 * ### The rule this run is judged by
 *
 * Adopt a larger batch only if prefill throughput improves by at least fifteen percent and
 * peak resident memory grows by no more than 250 MB. Below ten percent, or above 300 MB,
 * it stays at 512. Written down before the run so the result cannot be read generously
 * afterwards; the threshold is a judgement about a phone, where transient memory during
 * prefill is what the low memory killer sees.
 *
 * Prints rather than asserts. The verdict is a number to put in
 * `ModelLoadParams.DEFAULT_BATCH_TOKENS`, and it belongs there with the table that chose
 * it, not in a bound here.
 *
 * ```
 * adb push LFM2.5-2.6B-Q4_0.gguf /data/local/tmp/openweights/bench/lfm.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class BatchSizeBenchmark {
    private var engine: InferenceEngine? = null

    @After
    fun tearDown() {
        engine?.let { runBlocking { it.close() } }
    }

    @Test
    fun everyBatchPairIsMeasuredForPrefillSpeedAndForWhatItCostsInMemory() = runBlocking {
        val model = MODELS.firstOrNull { it.isFile }
        assumeTrue("no model under /data/local/tmp/openweights", model != null)
        requireNotNull(model)

        Log.i(TAG, "model ${model.name}")
        Log.i(TAG, "n_batch | n_ubatch | promptTok | prefill ms | tok/s | peak RSS MB")
        for (prompt in PROMPT_TOKENS) {
            val question = filler(prompt)
            for ((batch, micro) in PAIRS) {
                val fresh = LlamaCppEngine()
                engine?.close()
                engine = fresh
                fresh.load(
                    model,
                    ModelLoadParams(
                        contextLength = CONTEXT,
                        batchTokens = batch,
                        microBatchTokens = micro,
                    ),
                )

                val before = residentKb()
                val completed = fresh.chat(
                    messages = listOf(ChatMessage.text(ChatRole.USER, question)),
                    // One token of decode: this is a measurement of prefill, and decoding
                    // an answer would add a minute of unrelated work to every one of ten
                    // arms.
                    params = SamplerParams(temperature = 0f, maxTokens = 1, seed = 7),
                ).toList().filterIsInstance<GenerationEvent.Completed>().single()
                val peak = maxOf(before, residentKb())

                val stats = completed.stats
                val rate = if (stats.prefillMs > 0) {
                    stats.promptTokens * MILLIS_PER_SECOND / stats.prefillMs
                } else {
                    0.0
                }
                Log.i(
                    TAG,
                    "%d | %d | %d | %d | %.1f | %d".format(
                        batch,
                        micro,
                        stats.promptTokens,
                        stats.prefillMs,
                        rate,
                        peak / KB_PER_MB,
                    ),
                )
            }
        }
    }

    /**
     * A prompt of roughly [tokens] tokens, built from ordinary prose.
     *
     * Prose rather than a repeated word, because a repeated word tokenizes at a rate
     * nothing else does and would make the token count a fiction. The exact length does not
     * matter; every arm gets the same string, and the engine reports what it actually read.
     */
    private fun filler(tokens: Int): String {
        val sentence = "The cache keeps the head of the conversation so the phone does not " +
            "read it twice, which is the whole of why a second question is faster. "
        return buildString {
            while (length < tokens * CHARS_PER_TOKEN) append(sentence)
            append("\n\nReply with the single word: ready.")
        }
    }

    /**
     * This process's resident set, in kilobytes, read from the kernel rather than estimated.
     *
     * `VmHWM` is the high-water mark, which is the number that matters: the low memory
     * killer reacts to the peak during prefill, not to what is left afterwards.
     */
    private fun residentKb(): Long = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.filter { it.isDigit() }
            ?.toLongOrNull()
            ?: 0L
    }.getOrDefault(0L)

    private companion object {
        const val TAG = "OpenWeightsBatchSize"
        const val CONTEXT = 8192
        const val MILLIS_PER_SECOND = 1000.0
        const val KB_PER_MB = 1024

        /** Roughly four characters a token in English prose, which is close enough here. */
        const val CHARS_PER_TOKEN = 4

        val MODELS = listOf(
            File("/data/local/tmp/openweights/bench/lfm.gguf"),
            File("/data/local/tmp/openweights/model.gguf"),
        )

        /**
         * The pairs worth trying, logical batch first.
         *
         * The two that raise only the logical batch are there to separate the effects: if
         * 1024 by 512 is as fast as 1024 by 1024, the win was queueing rather than compute
         * and the scratch buffer never had to grow.
         */
        val PAIRS = listOf(
            512 to 512,
            1024 to 512,
            1024 to 1024,
            2048 to 512,
            2048 to 2048,
        )

        /**
         * Prompt lengths that bracket what this app actually sends.
         *
         * About two thousand tokens is a fresh conversation with the tool catalogue in it,
         * which the first-turn work measured; a thousand is a conversation without tools.
         * Below the batch size nothing can differ, so there is no shorter arm.
         */
        val PROMPT_TOKENS = listOf(1024, 2048)
    }
}

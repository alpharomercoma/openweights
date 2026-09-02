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
 * What draft-free speculation is worth on this phone, and whether it changes the answer.
 *
 * Two prompts over the same model, each decoded twice, with the n-gram drafter off and
 * on. One is the shape the drafter exists for: a page handed to the model to give back
 * with one change, so most of the reply is a copy of the prompt. The other is a plain
 * question the context says nothing about, where no draft should ever be proposed and
 * the cost should be nil. At temperature zero the verification is exact, so the reply
 * with the drafter on must be the reply without it, byte for byte; that is the
 * correctness half, and it is asserted. The speed half is logged: tokens per second
 * both ways, and the engine's own `spec:` line in logcat with drafted and accepted counts.
 *
 * The go rule in docs/research/qa-sweep-2026-09-02.md: on if the copy-heavy turn gains
 * fifteen percent of wall clock and the plain turn loses no more than two.
 *
 * Needs a transformer: the engine keeps the single path on a hybrid whatever the load
 * asks. Push one to `bench/`, the same file the prefix-reuse tests read:
 * ```
 * adb push Qwen3-1.7B-Q8_0.gguf /data/local/tmp/openweights/bench/qwen.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SpeculationBenchmark {

    @Test
    fun measuresWhatSpeculationIsWorth() = runBlocking {
        assumeTrue("no transformer at ${MODEL.path}", MODEL.isFile)

        for ((label, prompt) in listOf("copy" to COPY_PROMPT, "plain" to PLAIN_PROMPT)) {
            val plain = decode(prompt, speculation = false)
            val drafted = decode(prompt, speculation = true)
            Log.i(
                TAG,
                "%s: off %.1f tg/s (%d tokens) on %.1f tg/s (%d tokens) gain %.2fx".format(
                    label,
                    plain.rate,
                    plain.stats.generatedTokens,
                    drafted.rate,
                    drafted.stats.generatedTokens,
                    drafted.rate / plain.rate,
                ),
            )
            // Exact verification: the drafter can only make the same reply arrive sooner.
            assertThat(drafted.text).isEqualTo(plain.text)
        }
    }

    private suspend fun decode(prompt: String, speculation: Boolean): Decoded =
        LlamaCppEngine().use { engine ->
            engine.load(
                MODEL,
                ModelLoadParams(contextLength = CONTEXT, gpuLayers = 0, speculation = speculation),
            )
            // Warm, so the figure is throughput rather than first-call setup.
            engine.turn("Say hello.", WARM_UP_TOKENS)
            engine.resetContext()
            val events = engine.chat(
                messages = listOf(ChatMessage.text(ChatRole.USER, prompt)),
                params = SamplerParams(temperature = 0f, maxTokens = ANSWER_TOKENS, seed = 1),
            ).toList()
            val done = events.filterIsInstance<GenerationEvent.Completed>().single()
            Decoded(done.content, done.stats)
        }

    private suspend fun InferenceEngine.turn(prompt: String, maxTokens: Int) {
        chat(
            messages = listOf(ChatMessage.text(ChatRole.USER, prompt)),
            params = SamplerParams(temperature = 0f, maxTokens = maxTokens, seed = 1),
        ).toList()
    }

    private data class Decoded(val text: String, val stats: GenerationStats) {
        val rate: Double
            get() = stats.generatedTokens * MILLIS / stats.decodeMs.coerceAtLeast(1).toDouble()
    }

    private companion object {
        const val TAG = "OpenWeights"
        val MODEL = File("/data/local/tmp/openweights/bench/qwen.gguf")
        const val CONTEXT = 4096
        const val WARM_UP_TOKENS = 8
        const val ANSWER_TOKENS = 600
        const val MILLIS = 1000.0

        /** A page to give back with one change: most of the reply copies the prompt. */
        val COPY_PROMPT = buildString {
            append("Here is a web page. Return the complete page unchanged except that the ")
            append("title becomes \"Tide Tables\". Output only the HTML, nothing else.\n\n")
            append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
            append("<title>Harbour Notes</title>\n<style>\n")
            repeat(6) { index ->
                append("  .card-").append(index)
                append(" { margin: 12px; padding: 16px; border: 1px solid #cbd5e1; ")
                append("border-radius: 8px; background: #f8fafc; }\n")
            }
            append("</style>\n</head>\n<body>\n<main>\n")
            repeat(6) { index ->
                append("  <section class=\"card-").append(index).append("\">\n")
                append("    <h2>Berth ").append(index + 1).append("</h2>\n")
                append("    <p>Depth at low water is measured every morning and posted here ")
                append("before the first ferry leaves the harbour.</p>\n")
                append("    <ul>\n      <li>Morning: 3.2 m</li>\n      <li>Evening: 4.1 m</li>\n")
                append("    </ul>\n  </section>\n")
            }
            append("</main>\n</body>\n</html>\n")
        }

        /** Nothing in the context to copy from. */
        const val PLAIN_PROMPT = "Explain in a few paragraphs what a KV cache is and why it helps."
    }
}

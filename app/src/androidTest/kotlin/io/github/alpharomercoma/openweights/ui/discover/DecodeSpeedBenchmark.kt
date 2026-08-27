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

package io.github.alpharomercoma.openweights.ui.discover

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What codex and agy asked for after the last on-device measurements: repeated, controlled
 * runs, not one sample per model.
 *
 * A single chat reply per model told us the formula's real prediction error was between 23%
 * and 130% on this device, but not whether that spread is real or measurement noise — a
 * 178-260 token sample on a phone whose thermal state and background load were never
 * controlled could plausibly explain a third of that range by itself. This drives the same
 * prompt through the same three already-downloaded models, five repetitions each after a
 * discarded warmup, with a fixed token budget so every repetition decodes the same amount of
 * work. It reports median and spread per model directly to logcat rather than asserting
 * anything: the point is a number to read, not a pass/fail gate a stochastic decoder should
 * not be trusted to satisfy on a phone whose other apps are not being controlled for either.
 *
 * Run with:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=...DecodeSpeedBenchmark
 * and read the `OWBenchmark` tag in logcat.
 */
@RunWith(AndroidJUnit4::class)
class DecodeSpeedBenchmark {
    @Test
    fun measureDecodeSpeedAcrossInstalledModels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        assumeTrue("no models directory at $modelsDir", modelsDir.isDirectory)

        val models = MODEL_NAMES.mapNotNull { name ->
            File(modelsDir, name).takeIf { it.isFile }
        }
        assumeTrue("none of the expected models are downloaded", models.isNotEmpty())

        // Deliberately not the download order: LFM was the first model measured this
        // session and the last thing loaded before this run, so it starts warm. Running it
        // twice, once first and once last, is what tells us whether run order itself is a
        // source of drift rather than assuming it away.
        val order = listOf(
            models.first { it.name.startsWith("LFM2.5") },
            models.first { it.name.startsWith("Qwen3") },
            models.first { it.name.startsWith("granite") },
            models.first { it.name.startsWith("LFM2.5") },
        )

        val results = mutableListOf<String>()
        order.forEachIndexed { index, model ->
            val speeds = benchmarkOneModel(model)
            val label = "${model.name}#${index + 1}"
            val sorted = speeds.sorted()
            val median = sorted[sorted.size / 2]
            val line = "OWBenchmark $label bytes=${model.length()} " +
                "reps=${speeds.size} tps=${speeds.map { "%.2f".format(it) }} " +
                "median=%.2f min=%.2f max=%.2f".format(median, sorted.first(), sorted.last())
            Log.i(TAG, line)
            results += line
        }

        Log.i(TAG, "OWBenchmark DONE")
        // No assertion: this is a measurement run, not a correctness gate. See the class doc.
        assumeTrue(results.isNotEmpty())
    }

    /** One warmup rep (discarded), then [REPS] measured reps, all at a fixed token budget. */
    private suspend fun benchmarkOneModel(model: File): List<Double> {
        val engine: InferenceEngine = LlamaCppEngine()
        try {
            engine.load(model, ModelLoadParams(contextLength = CONTEXT_LENGTH))

            // Warmup: first inference after a cold load pays one-time costs (mmap page-in,
            // allocator growth) that a steady-state number should not be charged for.
            engine.resetContext()
            runOnce(engine)

            return (1..REPS).map {
                // Every rep starts from an empty cache, so every rep pays the same fixed
                // prefill and decodes the same fixed budget — a rep is never cheaper just
                // because the previous one happened to share a prefix with it.
                engine.resetContext()
                runOnce(engine).decodeTokensPerSecond ?: 0.0
            }
        } finally {
            engine.close()
        }
    }

    private suspend fun runOnce(engine: InferenceEngine) =
        engine.chat(
            messages = listOf(ChatMessage.text(ChatRole.USER, PROMPT)),
            params = SamplerParams(temperature = 0f, maxTokens = TOKEN_BUDGET, seed = 7),
        ).toList().filterIsInstance<GenerationEvent.Completed>().last().stats

    private companion object {
        const val TAG = "OWBenchmark"
        const val CONTEXT_LENGTH = 4096

        /** Long enough that every model here hits the cap rather than stopping early. */
        const val TOKEN_BUDGET = 150
        const val REPS = 5

        /** Reliably produces a long answer at temperature 0, so every rep decodes ~TOKEN_BUDGET. */
        const val PROMPT =
            "Write a detailed, several-paragraph explanation of how the water cycle works, " +
                "covering evaporation, condensation, precipitation, and collection."

        val MODEL_NAMES = listOf(
            "LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf",
            "Qwen3-1.7B-Q4_K_M.gguf",
            "granite-4.2-3b-Q2_K.gguf",
        )
    }
}

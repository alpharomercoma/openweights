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
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alpharomercoma.openweights.core.common.device.CpuTopology
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Where llama.cpp's time goes on a phone nobody here can hold.
 *
 * Written for two numbers the five-SoC matrix produced and could not explain: a Snapdragon
 * 8 Elite whose llama.cpp prefill ran at a tenth of every other phone's while its decode
 * was the fastest of all, and a Tensor G5 and an Exynos 2400 decoding at half their
 * ExecuTorch rate. Both are thread questions until measured otherwise, so this sweeps the
 * two thread counts one axis at a time, on a short prompt and a long one, and writes what
 * it saw beside the topology it saw it on. It runs on the first GGUF in the eval directory
 * and reports as `speed-probe`, a name no model family matches, so compare.py leaves the
 * file alone.
 */
@RunWith(AndroidJUnit4::class)
class SpeedProbe {

    @Test
    fun sweepThreads(): Unit = runBlocking {
        val model = EVAL_DIR.listFiles { file -> file.extension == "gguf" }
            ?.minByOrNull { it.name }
        assumeTrue("no .gguf files in $EVAL_DIR", model != null)
        model!!

        val out = JSONObject()
            .put("model", "speed-probe")
            .put("gguf", model.name)
            .put("cores", CpuTopology.allCores)
            .put("performance_cores", CpuTopology.performanceCores())
            .put("max_khz", JSONArray(maxFrequencies()))
        val runs = JSONArray()

        val engine = LlamaCppEngine()
        try {
            engine.load(model, ModelLoadParams(contextLength = CONTEXT))
            out.put("system_info", engine.systemInfo())
            Log.i(TAG, "system: ${engine.systemInfo()}")

            val long = "Summarise the following in one sentence. " +
                "The quick brown fox jumps over the lazy dog near the riverbank at dawn. "
                    .repeat(LONG_REPEATS)
            val short = "What is the capital of Japan? Answer in one word."

            // Prefill first: batch threads move, generation threads stay at the app's default.
            for (batch in THREADS) {
                runs.put(
                    measure(
                        engine,
                        "prefill",
                        genThreads = DEFAULT_GEN,
                        batchThreads = batch,
                        prompt = long,
                    ),
                )
            }
            // Then decode: generation threads move, batch threads stay at the app's default.
            for (gen in THREADS) {
                runs.put(
                    measure(
                        engine,
                        "decode",
                        genThreads = gen,
                        batchThreads = defaultBatch,
                        prompt = short,
                    ),
                )
            }
            // The short prompt twice at the defaults: a fixed cost per call shows here as
            // a prefill rate that does not move with the prompt.
            repeat(2) {
                runs.put(
                    measure(
                        engine,
                        "short",
                        genThreads = DEFAULT_GEN,
                        batchThreads = defaultBatch,
                        prompt = short,
                    ),
                )
            }
        } finally {
            engine.unload()
            engine.close()
        }
        out.put("runs", runs)

        val dir = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null)!!.resolve("eval-results")
        dir.mkdirs()
        val file = dir.resolve("speed-probe.json")
        file.writeText(out.toString(2))
        Log.i(TAG, "wrote ${file.absolutePath}")
    }

    private suspend fun measure(
        engine: LlamaCppEngine,
        axis: String,
        genThreads: Int,
        batchThreads: Int,
        prompt: String,
    ): JSONObject {
        engine.setThreads(genThreads, batchThreads)
        engine.resetContext()
        val events = engine.chat(listOf(ChatMessage.text(ChatRole.USER, prompt)), PARAMS).toList()
        val done = events.filterIsInstance<GenerationEvent.Completed>().single()
        val stats = done.stats
        val prefill = stats.promptTokens.toDouble() / stats.prefillMs * MILLIS
        val decode = (stats.generatedTokens - 1).toDouble() / stats.decodeMs * MILLIS
        Log.i(
            TAG,
            "$axis gen=$genThreads batch=$batchThreads prompt=${stats.promptTokens} " +
                "prefill=${stats.prefillMs}ms (${"%.1f".format(prefill)} tok/s) " +
                "decode=${stats.generatedTokens} in ${stats.decodeMs}ms (${"%.1f".format(
                    decode,
                )} tok/s)",
        )
        return JSONObject()
            .put("axis", axis)
            .put("gen_threads", genThreads)
            .put("batch_threads", batchThreads)
            .put("prompt_tokens", stats.promptTokens)
            .put("prefill_ms", stats.prefillMs)
            .put("generated_tokens", stats.generatedTokens)
            .put("decode_ms", stats.decodeMs)
            .put("prefill_tok_s", prefill)
            .put("decode_tok_s", decode)
    }

    // The engine's own defaults, computed the way LlamaCppEngine computes them: half the
    // cores for generation within 2..6, the performance cores for a batch within 2..8.
    private val defaultBatch = CpuTopology.performanceCores().coerceIn(2, 8)

    private fun maxFrequencies(): List<Long> = (0 until CpuTopology.allCores).map { core ->
        runCatching {
            File(
                "/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq",
            ).readText().trim().toLong()
        }.getOrDefault(-1L)
    }

    private companion object {
        const val TAG = "SpeedProbe"
        const val CONTEXT = 4096
        const val LONG_REPEATS = 24
        const val MILLIS = 1000.0
        val THREADS = listOf(2, 4, 6, 8)
        val DEFAULT_GEN = (CpuTopology.allCores / 2).coerceIn(2, 6)
        val EVAL_DIR = File("/data/local/tmp/openweights/eval")
        val PARAMS =
            SamplerParams(temperature = 0f, topK = 1, seed = 7, maxTokens = 48, thinking = false)
    }
}

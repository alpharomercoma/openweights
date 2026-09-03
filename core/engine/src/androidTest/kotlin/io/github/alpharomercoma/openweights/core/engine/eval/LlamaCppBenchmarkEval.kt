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
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [BenchmarkSuite] over the pushed GGUFs, on llama.cpp. Instrumentation arguments
 * `model`, `sets`, `limit` and `budget` narrow the run; see [BenchmarkSuite.Options].
 */
@RunWith(AndroidJUnit4::class)
class LlamaCppBenchmarkEval {

    @Test
    fun benchmarkEveryPushedGguf(): Unit = runBlocking {
        val options = BenchmarkSuite.optionsFrom(InstrumentationRegistry.getArguments())
        val models = EVAL_DIR.listFiles { f ->
            f.extension == "gguf" && options.model in f.name
        }.orEmpty()
        assumeTrue("no matching .gguf files in $EVAL_DIR", models.isNotEmpty())
        val prompts = EVAL_DIR.resolve(PROMPTS)
        assumeTrue("no $PROMPTS in $EVAL_DIR", prompts.isFile)
        val resultsDir = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null)!!.resolve("eval-results")

        BenchmarkSuite.startClock(options)
        models.sortedBy { it.name }.forEach { model ->
            Log.i(TAG, "benchmarking ${model.name}")
            val engine = LlamaCppEngine()
            try {
                engine.load(model, ModelLoadParams(contextLength = CONTEXT))
                val out = BenchmarkSuite.run(
                    engine,
                    model.nameWithoutExtension,
                    prompts,
                    resultsDir,
                    options,
                )
                Log.i(TAG, "wrote ${out.absolutePath}")
                assertThat(out.isFile).isTrue()
            } finally {
                engine.unload()
                engine.close()
            }
        }
    }

    private companion object {
        const val TAG = "LlamaCppBenchmarkEval"
        const val CONTEXT = 4096
        const val PROMPTS = "benchmarks.json"
        val EVAL_DIR = File("/data/local/tmp/openweights/eval")
    }
}

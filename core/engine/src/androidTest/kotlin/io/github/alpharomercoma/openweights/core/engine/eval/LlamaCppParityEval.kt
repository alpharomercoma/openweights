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
 * [ParitySuite] over every GGUF pushed to the eval directory, on llama.cpp.
 *
 * Not a pass/fail gate on model quality: each model's grades land in a JSON report for
 * `tools/eval/compare.py` to diff against its ExecuTorch counterpart. The only failure
 * this test itself can produce is the harness failing — a model that cannot load, or a
 * turn that throws. Push models first:
 * ```
 * adb push X.gguf /data/local/tmp/openweights/eval/X.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LlamaCppParityEval {

    @Test
    fun evaluateEveryPushedGguf(): Unit = runBlocking {
        val models = EVAL_DIR.listFiles { file -> file.extension == "gguf" }.orEmpty()
        assumeTrue("no .gguf files in $EVAL_DIR", models.isNotEmpty())

        val resultsDir = InstrumentationRegistry.getInstrumentation()
            .targetContext.filesDir.resolve("eval-results")

        models.sortedBy { it.name }.forEach { model ->
            Log.i(TAG, "evaluating ${model.name}")
            val engine = LlamaCppEngine()
            try {
                engine.load(model, ModelLoadParams(contextLength = CONTEXT))
                val out = ParitySuite.run(engine, model.nameWithoutExtension, resultsDir)
                Log.i(TAG, "wrote ${out.absolutePath}")
                assertThat(out.isFile).isTrue()
            } finally {
                engine.unload()
                engine.close()
            }
        }
    }

    private companion object {
        const val TAG = "LlamaCppParityEval"
        const val CONTEXT = 4096
        val EVAL_DIR = File("/data/local/tmp/openweights/eval")
    }
}

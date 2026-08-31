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

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.engine.ExecuTorchEngine
import io.github.alpharomercoma.openweights.core.engine.NativeExecuTorchBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [WebsiteBuild] on the compiled runtime: the same loop, the same grading, a `.pte`.
 */
@RunWith(AndroidJUnit4::class)
class ExecuTorchWebsiteBuildEval {

    @Test
    fun buildsAWorkingWebsite(): Unit = runBlocking {
        val model = WebsiteBuild.EVAL_DIR
            .listFiles { file -> file.name == PTE_BUILDER }?.firstOrNull()
        assumeTrue("no $PTE_BUILDER in ${WebsiteBuild.EVAL_DIR}", model != null)

        val engine = ExecuTorchEngine(NativeExecuTorchBridge(), temperature = 0f)
        try {
            engine.load(model!!, ModelLoadParams(contextLength = 2048))
            WebsiteBuild.build(engine)
        } finally {
            engine.unload()
            engine.close()
        }
    }

    private companion object {
        const val PTE_BUILDER = "Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.pte"
    }
}

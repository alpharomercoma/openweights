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
 *
 * Skipped until a Qwen3 export with a 4096-token window is pushed, and that is a
 * measurement, not a shrug. On the published 2048-token export both modes were driven
 * to their edge on this phone: with reasoning on, the model spends the entire window
 * thinking about the page (2,019 characters of deliberation for a two-line site) and is
 * cut before its first tool call; with reasoning off, the INT4 weights greedily sample
 * an end-of-turn token exactly 24 tokens into the call's JSON, deterministically, on
 * two different prompts. The same model, same engine, passes the whole parity suite
 * including the tool loop — short calls fit the window, a built page does not. The fix
 * is an export whose window affords the errand: `tools/executorch/export_qwen3.sh`
 * with `+export.max_seq_length=4096`, on the Linux build host.
 */
@RunWith(AndroidJUnit4::class)
class ExecuTorchWebsiteBuildEval {

    @Test
    fun buildsAWorkingWebsite(): Unit = runBlocking {
        val model = WebsiteBuild.EVAL_DIR
            .listFiles { file -> file.name.matches(PTE_BUILDER) }?.firstOrNull()
        assumeTrue(
            "no 4k-window Qwen3 export in ${WebsiteBuild.EVAL_DIR}; see the class doc",
            model != null,
        )

        val engine = ExecuTorchEngine(NativeExecuTorchBridge(), temperature = 0f)
        try {
            engine.load(model!!, ModelLoadParams(contextLength = 4096))
            WebsiteBuild.build(engine)
        } finally {
            engine.unload()
            engine.close()
        }
    }

    private companion object {
        val PTE_BUILDER = Regex("Qwen3.*4096.*\\.pte", RegexOption.IGNORE_CASE)
    }
}

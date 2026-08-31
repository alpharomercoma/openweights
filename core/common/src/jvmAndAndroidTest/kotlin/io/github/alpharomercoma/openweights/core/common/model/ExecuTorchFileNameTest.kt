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

package io.github.alpharomercoma.openweights.core.common.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExecuTorchFileNameTest {

    @Test
    fun `a file with a name of its own joins the repository name`() {
        val name = ExecuTorchFileName.modelNameFor(
            "software-mansion/react-native-executorch-smolLm-2",
            "1_7b/xnnpack/smollm2_1_7b_xnnpack_8da4w.pte",
        )

        // Distinct per file, or the second size downloaded from this repository would
        // silently overwrite the first — and still carrying "smollm2" for the template.
        assertThat(name)
            .isEqualTo("react-native-executorch-smolLm-2-smollm2_1_7b_xnnpack_8da4w.pte")
    }

    @Test
    fun `a generic model file in a folder takes the folder's name`() {
        // codex QA: a repository holding 1b/model.pte and 3b/model.pte installed both
        // under one name, and the second download silently replaced the first.
        val name = ExecuTorchFileName.modelNameFor("someone/two-sizes", "3b/xnnpack/model.pte")

        assertThat(name).isEqualTo("two-sizes-3b-xnnpack.pte")
    }

    @Test
    fun `the official single model file keeps the repository name alone`() {
        // What every already-installed model was saved as, so it must not change.
        assertThat(ExecuTorchFileName.modelNameFor("pytorch/Qwen3-1.7B-INT8-INT4", "model.pte"))
            .isEqualTo("Qwen3-1.7B-INT8-INT4.pte")
    }

    @Test
    fun `names the weights after the repository, not the file inside it`() {
        val name = ExecuTorchFileName.modelNameFor(
            "larryliu0820/Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK",
        )

        // Every .pte repository on the Hub calls its weights `model.pte`, so keeping the
        // remote name would collide on the second install and, worse, leave nothing for
        // PromptTemplates to read: a .pte carries no metadata saying which family it is.
        assertThat(name).isEqualTo("Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.pte")
        assertThat(PromptTemplates.forModel(name)).isNotNull()
    }

    @Test
    fun `refuses to let a repository name escape the models directory`() {
        // A repository is a stranger's data and its name reaches a file path.
        assertThat(ExecuTorchFileName.modelNameFor("someone/..")).isEqualTo("model.pte")
        assertThat(ExecuTorchFileName.modelNameFor("someone/../../etc/passwd"))
            .isEqualTo("passwd.pte")
        assertThat(ExecuTorchFileName.modelNameFor("""someone/a\b"""))
            .isEqualTo("a-b.pte")
    }

    @Test
    fun `pairs a tokenizer with the model it was exported against`() {
        val model = "Qwen3-1.7B-ExecuTorch-XNNPACK.pte"

        val tokenizer = ExecuTorchFileName.tokenizerNameFor(model)

        assertThat(tokenizer).isEqualTo("Qwen3-1.7B-ExecuTorch-XNNPACK.tokenizer.json")
        assertThat(ExecuTorchFileName.isTokenizer(tokenizer)).isTrue()
        // A tokenizer is not itself something to load.
        assertThat(ModelFormat.of(tokenizer)).isNull()
    }

    @Test
    fun `recognises the names a repository publishes its tokenizer under`() {
        assertThat(ExecuTorchFileName.isRemoteTokenizer("tokenizer.json")).isTrue()
        assertThat(ExecuTorchFileName.isRemoteTokenizer("tokenizer.model")).isTrue()
        // Present in the same repositories and not what ExecuTorch wants.
        assertThat(ExecuTorchFileName.isRemoteTokenizer("tokenizer_config.json")).isFalse()
        assertThat(ExecuTorchFileName.isRemoteTokenizer("vocab.json")).isFalse()
    }

    @Test
    fun `tells the two formats apart`() {
        assertThat(ModelFormat.of("model.pte")).isEqualTo(ModelFormat.PTE)
        assertThat(ModelFormat.of("Qwen3-1.7B-Q4_K_M.gguf")).isEqualTo(ModelFormat.GGUF)
        assertThat(ModelFormat.of("README.md")).isNull()
    }
}

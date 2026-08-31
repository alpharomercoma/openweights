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

package io.github.alpharomercoma.openweights.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.engine.ExecuTorchSupport
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Which models on disk this build will offer to open.
 *
 * Written to hold in **both** product flavours rather than in one, which is why the
 * assertions compare against [ExecuTorchSupport.AVAILABLE] instead of against `true`. That
 * constant is the only difference between the two builds, so a test that pinned it would
 * pass in one variant and fail in the other, and `verify` runs both.
 */
@RunWith(RobolectricTestRunner::class)
class ModelStoreRuntimeTest {

    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        store = ModelStore(ApplicationProvider.getApplicationContext<Context>())
        store.directory.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `always offers a gguf`() {
        write("LFM2.5-1.2B-Q4_0.gguf")

        assertThat(store.availableModels().map { it.name })
            .containsExactly("LFM2.5-1.2B-Q4_0.gguf")
    }

    @Test
    fun `never offers compiled weights whose tokenizer has not arrived`() {
        write("Qwen3-1.7B-ExecuTorch-XNNPACK.pte")

        // True in either flavour. The two files download separately, and weights alone
        // open into a failure, so the row must not appear between the two arriving.
        assertThat(store.availableModels()).isEmpty()
    }

    @Test
    fun `offers compiled weights once both files are present, if this build can run them`() {
        write("Qwen3-1.7B-ExecuTorch-XNNPACK.pte")
        write("Qwen3-1.7B-ExecuTorch-XNNPACK.tokenizer.json")

        val offered = store.availableModels().map { it.name }

        assertThat(offered.contains("Qwen3-1.7B-ExecuTorch-XNNPACK.pte"))
            .isEqualTo(ExecuTorchSupport.AVAILABLE)
        // The tokenizer is never a model in its own right, in either flavour.
        assertThat(offered).doesNotContain("Qwen3-1.7B-ExecuTorch-XNNPACK.tokenizer.json")
    }

    @Test
    fun `saves a repository's weights under the repository's own name`() {
        val destination = store.compiledDestination(
            "larryliu0820/Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK",
        )

        // Not `model.pte`, which is what the file is called in every such repository: two
        // installs would collide, and the family a .pte belongs to is readable only from
        // its name.
        assertThat(destination.name).isEqualTo("Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.pte")
        assertThat(destination.parentFile).isEqualTo(store.directory)
        assertThat(store.tokenizerFor(destination.name).name)
            .isEqualTo("Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.tokenizer.json")
    }

    private fun write(name: String) {
        File(store.directory, name).writeText("x")
    }
}

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

package io.github.alpharomercoma.openweights.core.hub

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.CompiledBackend
import io.github.alpharomercoma.openweights.core.common.model.ExecuTorchFileName
import io.github.alpharomercoma.openweights.core.common.model.PromptTemplates
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The detail page's offer, computed the way `DiscoverViewModel` computes it, against the
 * real layouts publishers use for compiled weights.
 *
 * A phone showed `software-mansion/react-native-executorch-lfm2.5-1.2B-instruct` with a
 * licence line and nothing to download (2026-09-06). That repository keeps its weights
 * under `quantized/` and `original/` with one root tokenizer, which is neither the
 * official one-`model.pte` layout nor software-mansion's older per-size layout. This
 * walks every filter the screen applies and says which one, if any, empties the offer.
 *
 * Network, so opt-in like [HubIntegrationTest]: `OPENWEIGHTS_NETWORK_TESTS=1`.
 */
class HubCompiledLayoutsTest {
    @Before
    fun requireNetworkTestsEnabled() {
        assumeTrue(
            "set OPENWEIGHTS_NETWORK_TESTS=1 to run tests that call Hugging Face",
            System.getenv("OPENWEIGHTS_NETWORK_TESTS") == "1",
        )
    }

    private val client = HuggingFaceClient(OkHttpClient(), HubTokenSource { null })

    private val repos = listOf(
        "software-mansion/react-native-executorch-lfm2.5-1.2B-instruct",
        "software-mansion/react-native-executorch-lfm2.5-350M",
        "software-mansion/react-native-executorch-lfm-2.5",
        "larryliu0820/Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK",
        "software-mansion/react-native-executorch-llama-3.2",
        "pytorch/SmolLM3-3B-INT8-INT4",
    )

    @Test
    fun `every publisher layout leaves something to download`() = runBlocking {
        val backends = setOf(CompiledBackend.XNNPACK, CompiledBackend.UNKNOWN)
        val failures = mutableListOf<String>()
        repos.forEach { repoId ->
            val detail = client.detail(repoId)
            val steps = detail.compiled.map { file ->
                val backend = CompiledBackend.of(repoId + file.path)
                val name = ExecuTorchFileName.modelNameFor(repoId, file.path)
                listOf(
                    "installable" to detail.isInstallableCompiled,
                    "backend $backend" to (backend in backends),
                    "tokenizer" to (detail.tokenizerFor(file) != null),
                    "template for $name" to (PromptTemplates.forModel(name) != null),
                )
            }
            val offered = steps.count { s -> s.all { it.second } }
            println(
                "$repoId: ${detail.compiled.size} compiled, ${detail.tokenizers.size} tokenizers, $offered offered",
            )
            detail.compiled.zip(steps).forEach { (file, s) ->
                println("   ${file.path}: " + s.joinToString(" | ") { "${it.first}=${it.second}" })
            }
            if (offered == 0) failures += repoId
        }
        assertThat(failures).isEmpty()
    }

    @Test
    fun `every row on the compiled shortlist has something to download`() = runBlocking {
        val backends = setOf(CompiledBackend.XNNPACK, CompiledBackend.UNKNOWN)
        RECOMMENDED.filter {
            "executorch" in it.lowercase() || "INT8-INT4" in it
        }.forEach { repoId ->
            val detail = client.detail(repoId)
            val offered = detail.compiled.filter { file ->
                CompiledBackend.of(repoId + file.path) in backends &&
                    detail.tokenizerFor(file) != null &&
                    PromptTemplates.forModel(ExecuTorchFileName.modelNameFor(repoId, file.path)) !=
                    null
            }
            println("$repoId offers " + offered.map { it.path })
            assertThat(offered).isNotEmpty()
            // Software Mansion ships MLX exports beside the XNNPACK ones. Never here.
            assertThat(offered.none { "mlx" in it.path }).isTrue()
        }
    }
}

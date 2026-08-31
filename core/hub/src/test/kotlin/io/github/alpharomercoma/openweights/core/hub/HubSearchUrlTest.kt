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
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * What each runtime's search actually asks the Hub.
 *
 * The distinction under test is the size band. `num_parameters` filters on metadata the Hub
 * reads out of safetensors files, and a compiled repository holds a `.pte` and a tokenizer
 * and nothing the Hub can count, so sending the band on the ExecuTorch half silently removed
 * almost the entire compiled corner — measured live, `filter=executorch&num_parameters=max:10B`
 * returned 16 stray repositories and none of the executorch-community ones. The user saw
 * that as "only SmolLM2 shows up", which is the worst kind of bug: the models were runnable
 * and invisible.
 */
class HubSearchUrlTest {
    private val client = HuggingFaceClient(OkHttpClient(), HubTokenSource { null })

    private val banded = HubQuery(
        runtimes = HubRuntime.entries.toSet(),
        maxParametersBillions = 10,
        officialOnly = true,
        recommendedOnly = false,
    )

    @Test
    fun `the llama cpp half carries the size band`() {
        val url = client.searchUrl(banded, HubRuntime.LLAMA_CPP)

        assertThat(url.queryParameter("apps")).isEqualTo("llama.cpp")
        assertThat(url.queryParameter("num_parameters")).isEqualTo("max:10B")
    }

    @Test
    fun `the executorch half never carries the size band`() {
        val url = client.searchUrl(banded, HubRuntime.EXECUTORCH)

        assertThat(url.queryParameter("filter")).isEqualTo("executorch")
        assertThat(url.queryParameter("num_parameters")).isNull()
    }

    @Test
    fun `everything else survives on both halves`() {
        val query = banded.copy(text = "qwen", hideGated = true)

        HubRuntime.entries.forEach { runtime ->
            val url = client.searchUrl(query, runtime)
            assertThat(url.queryParameter("search")).isEqualTo("qwen")
            assertThat(url.queryParameter("gated")).isEqualTo("false")
            assertThat(url.queryParameter("sort")).isNotEmpty()
        }
    }
}

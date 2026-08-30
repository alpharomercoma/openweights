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
import org.junit.Test

/**
 * How a compiled weights file finds the tokenizer it was exported against.
 *
 * The layouts here are real: the official `pytorch` repositories keep one `tokenizer.json` at the
 * root; software-mansion's LFM repository keeps one beside each size. Pairing a size with
 * another size's tokenizer loads fine and then speaks noise, which is why nearest-wins.
 */
class HubTokenizerTest {

    @Test
    fun `a root tokenizer serves every file`() {
        val detail = detail(
            compiled = listOf("1_7b/xnnpack/smollm2_1_7b_xnnpack_8da4w.pte"),
            tokenizers = listOf("tokenizer.json"),
        )

        val weights = detail.compiled.first()
        assertThat(detail.tokenizerFor(weights)?.path).isEqualTo("tokenizer.json")
    }

    @Test
    fun `the tokenizer beside a size outranks the root one`() {
        val detail = detail(
            compiled = listOf("1_2b/xnnpack/lfm_2_5_1_2b_xnnpack_8da4w.pte"),
            tokenizers = listOf("tokenizer.json", "1_2b/tokenizer.json", "350m/tokenizer.json"),
        )

        val weights = detail.compiled.first()
        assertThat(detail.tokenizerFor(weights)?.path).isEqualTo("1_2b/tokenizer.json")
    }

    @Test
    fun `another size's tokenizer is never borrowed`() {
        val detail = detail(
            compiled = listOf("1_2b/xnnpack/lfm_2_5_1_2b_xnnpack_8da4w.pte"),
            tokenizers = listOf("350m/tokenizer.json"),
        )

        assertThat(detail.tokenizerFor(detail.compiled.first())).isNull()
    }

    @Test
    fun `the JSON form outranks the model form beside the same file`() {
        val detail = detail(
            compiled = listOf("model.pte"),
            tokenizers = listOf("tokenizer.model", "tokenizer.json"),
        )

        assertThat(detail.tokenizerFor(detail.compiled.first())?.path)
            .isEqualTo("tokenizer.json")
    }

    private fun detail(compiled: List<String>, tokenizers: List<String>) = HubModelDetail(
        model = HubModel(
            id = "someone/some-model",
            downloads = 0,
            likes = 0,
            isGated = false,
            tags = emptyList(),
            updatedAt = null,
        ),
        files = emptyList(),
        projectors = emptyList(),
        compiled = compiled.map { HubFile(it, 1L, null) },
        tokenizers = tokenizers.map { HubFile(it, 1L, null) },
        license = null,
        architecture = null,
        parameterCount = null,
        trainingContextLength = null,
    )
}

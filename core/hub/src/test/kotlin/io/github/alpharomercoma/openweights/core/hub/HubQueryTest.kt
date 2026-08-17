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
 * What the search controls turn into.
 *
 * The live tests prove the Hub accepts these; these prove the app sends what it meant to,
 * without a network.
 */
class HubQueryTest {
    @Test
    fun `an unconstrained query asks for no size band`() {
        assertThat(HubQuery().parameterBand).isNull()
    }

    @Test
    fun `a band becomes the Hub's min and max form`() {
        assertThat(HubQuery(parameters = ParameterRange.MEDIUM).parameterBand)
            .isEqualTo("min:4B,max:8B")
        assertThat(HubQuery(parameters = ParameterRange.TINY).parameterBand).isEqualTo("max:2B")
        assertThat(HubQuery(parameters = ParameterRange.HUGE).parameterBand).isEqualTo("min:16B")
    }

    @Test
    fun `the device ceiling wins over a band`() {
        // Both set is a state the UI does not produce, but if it ever did, showing results
        // for one while the other is ticked would be the worst outcome.
        val query = HubQuery(parameters = ParameterRange.HUGE, maxParametersBillions = 11)

        assertThat(query.parameterBand).isEqualTo("max:11B")
    }

    @Test
    fun `the filter count is what the button shows`() {
        // One by default, because Recommended is on by default: the screen opens narrowed
        // to a measured shortlist and the button has to say so, or the count is a lie on
        // the first screen anybody sees.
        assertThat(HubQuery().activeCount).isEqualTo(1)
        assertThat(HubQuery(recommendedOnly = false).activeCount).isEqualTo(0)
        // Sort and text are not filters: they are always set and would leave the button
        // permanently claiming the list is narrowed.
        assertThat(
            HubQuery(text = "qwen", sort = HubSort.LIKES, recommendedOnly = false).activeCount,
        ).isEqualTo(0)
        assertThat(
            HubQuery(
                task = HubTask.VISION,
                author = "unsloth",
                hideGated = true,
                recommendedOnly = false,
            ).activeCount,
        ).isEqualTo(3)
    }

    @Test
    fun `a parameter count is read out of the repository name`() {
        assertThat(model("LiquidAI/LFM2.5-2.6B-GGUF").parameterHint).isEqualTo("2.6B")
        assertThat(model("unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF").parameterHint)
            .isEqualTo("30B")
        assertThat(model("HuggingFaceTB/SmolLM3-135M-GGUF").parameterHint).isEqualTo("135M")
    }

    @Test
    fun `a name with no size in it claims none`() {
        // Version numbers and quantization labels are not parameter counts, and a row that
        // says "4B" about a model of unknown size is worse than a row that says nothing.
        assertThat(model("google/gemma-7b").parameterHint).isEqualTo("7B")
        assertThat(model("mradermacher/Something-i1-GGUF").parameterHint).isNull()
        assertThat(model("someone/Model-Q4_K_M-GGUF").parameterHint).isNull()
    }

    @Test
    fun `the task tag says what the model can be given`() {
        assertThat(model("a/b", task = "image-text-to-text").isVision).isTrue()
        assertThat(model("a/b", task = "audio-text-to-text").isAudio).isTrue()
        assertThat(model("a/b", task = "any-to-any").isVision).isTrue()
        assertThat(model("a/b", task = "any-to-any").isAudio).isTrue()
        assertThat(model("a/b", task = "text-generation").isVision).isFalse()
        assertThat(model("a/b").isVision).isFalse()
    }

    private fun model(id: String, task: String? = null) = HubModel(
        id = id,
        downloads = 0,
        likes = 0,
        isGated = false,
        tags = emptyList(),
        updatedAt = null,
        pipelineTag = task,
    )
}

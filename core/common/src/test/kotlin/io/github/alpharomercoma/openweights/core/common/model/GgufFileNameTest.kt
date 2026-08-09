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

/**
 * Pairing a model with its projector.
 *
 * The consequence of getting this wrong is quiet: the wrong encoder loads without
 * complaint and the model then describes a picture it never saw. So the rules are tested
 * against the naming real publishers use, including the parts they are inconsistent about.
 */
class GgufFileNameTest {

    @Test
    fun `a projector is recognised by its prefix, whatever its case`() {
        assertThat(GgufFileName.isProjector("mmproj-LFM2.5-VL-1.6b-Q8_0.gguf")).isTrue()
        assertThat(GgufFileName.isProjector("MMPROJ-F16.gguf")).isTrue()
        assertThat(GgufFileName.isProjector("LFM2.5-VL-1.6B-Q4_K_M.gguf")).isFalse()
    }

    @Test
    fun `the quantization suffix is dropped from a model name`() {
        assertThat(GgufFileName.modelIdentity("LFM2.5-VL-1.6B-Q4_K_M.gguf"))
            .isEqualTo("LFM2.5-VL-1.6B")
        assertThat(GgufFileName.modelIdentity("Qwen3-VL-4B-Instruct-IQ3_XXS.gguf"))
            .isEqualTo("Qwen3-VL-4B-Instruct")
        assertThat(GgufFileName.modelIdentity("gemma-3-4b-it-BF16.gguf")).isEqualTo("gemma-3-4b-it")
        // Plain F16 is the most common projector precision on the Hub, and the easiest
        // alternation to write in a way that quietly only matches BF16.
        assertThat(GgufFileName.modelIdentity("mmproj-SmolVLM-256M-F16.gguf"))
            .isEqualTo("SmolVLM-256M")
    }

    @Test
    fun `a model and its projector resolve to the same identity`() {
        val model = GgufFileName.modelIdentity("LFM2.5-VL-1.6B-Q4_K_M.gguf")
        val projector = GgufFileName.modelIdentity("mmproj-LFM2.5-VL-1.6b-Q8_0.gguf")

        // Publishers do not agree with themselves about capitalisation, so matching has to
        // be case-insensitive. The real LiquidAI repository spells it both ways.
        assertThat(projector).isEqualTo("LFM2.5-VL-1.6b")
        assertThat(projector).isNotEqualTo(model)
        assertThat(projector.equals(model, ignoreCase = true)).isTrue()
    }

    @Test
    fun `a name with no recognised quantization keeps all of itself`() {
        assertThat(GgufFileName.modelIdentity("some-model.gguf")).isEqualTo("some-model")
        // A bare projector carries no identity to match on, which is why the caller falls
        // back to "this repository has exactly one" rather than pairing it with anything.
        assertThat(GgufFileName.modelIdentity("mmproj-F16.gguf")).isEmpty()
    }

    @Test
    fun `the quantization is the part the identity leaves behind`() {
        assertThat(GgufFileName.quantization("LFM2.5-VL-1.6B-Q4_K_M.gguf")).isEqualTo("Q4_K_M")
        assertThat(GgufFileName.quantization("gemma-3-4b-it-BF16.gguf")).isEqualTo("BF16")
        assertThat(GgufFileName.quantization("mmproj-F16.gguf")).isEqualTo("F16")
        assertThat(GgufFileName.quantization("some-model.gguf")).isNull()
    }

    @Test
    fun `a projector is saved under a name derived from its model`() {
        assertThat(GgufFileName.projectorNameFor("LFM2.5-VL-1.6B-Q4_K_M.gguf"))
            .isEqualTo("mmproj-LFM2.5-VL-1.6B-Q4_K_M.gguf")
    }

    @Test
    fun `the saved name round-trips back to a projector`() {
        val saved = GgufFileName.projectorNameFor("Qwen3-VL-4B-Instruct-Q4_K_M.gguf")

        assertThat(GgufFileName.isProjector(saved)).isTrue()
        assertThat(GgufFileName.modelIdentity(saved)).isEqualTo("Qwen3-VL-4B-Instruct")
    }
}

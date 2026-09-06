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
 * Family recognition over the names the catalogue actually publishes.
 *
 * Every name here is a real installed-file name derived from a real repository, because
 * the matcher works on names and the names are somebody else's: the test is that each
 * publisher's spelling reaches the right template, and that lookalike families are
 * refused rather than approximated.
 */
class PromptTemplatesTest {

    @Test
    fun `recognises every publisher spelling of the supported families`() {
        val expectations = mapOf(
            "Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.pte" to "<|im_end|>",
            "react-native-executorch-qwen-3-qwen_3_1_7b_xnnpack_8da4w.pte" to "<|im_end|>",
            "react-native-executorch-qwen-2.5.pte" to "<|im_end|>",
            "react-native-executorch-smolLm-2-smollm2_1_7b_xnnpack_8da4w.pte" to "<|im_end|>",
            "SmolLM3-3B-INT8-INT4.pte" to "<|im_end|>",
            "react-native-executorch-llama-3.2-llama_3_2_1b_xnnpack_bf16.pte" to "<|eot_id|>",
            "Phi-4-mini-instruct-INT8-INT4.pte" to "<|end|>",
            "gemma-3-4b-it-HQQ-INT8-INT4.pte" to "<end_of_turn>",
            "react-native-executorch-lfm-2.5-lfm_2_5_1_2b_xnnpack_8da4w.pte" to "<|im_end|>",
        )

        expectations.forEach { (fileName, marker) ->
            val template = PromptTemplates.forModel(fileName)
            assertThat(template).isNotNull()
            assertThat(template!!.stopMarkers).contains(marker)
        }
    }

    @Test
    fun `refuses families that merely look like supported ones`() {
        // Qwen3.5 normalises to a string containing "qwen3", and it is a different
        // family with a template nobody transcribed. Guessing would produce a model
        // that answers slightly wrongly forever, which is the failure this refuses.
        assertThat(PromptTemplates.forModel("Qwen3.5-0.8B-ExecuTorch.pte")).isNull()
        assertThat(PromptTemplates.forModel("react-native-executorch-qwen-3.5.pte")).isNull()
        assertThat(PromptTemplates.forModel("react-native-executorch-gemma-4.pte")).isNull()
        assertThat(PromptTemplates.forModel("react-native-executorch-bielik-v3.0.pte")).isNull()
        // Variants whose protocol differs from the base family's: the family token is in
        // the name, and the template would still be wrong (codex QA).
        assertThat(PromptTemplates.forModel("Qwen2.5-VL-3B-Instruct.pte")).isNull()
        assertThat(PromptTemplates.forModel("Qwen3-Coder-Next.pte")).isNull()
        assertThat(PromptTemplates.forModel("Llama-3.2-11B-Vision-Instruct.pte")).isNull()
        assertThat(PromptTemplates.forModel("Llama-Guard-3-1B-INT4.pte")).isNull()
    }

    @Test
    fun `LFM2 5-VL renders as LFM2 5, and other vision families stay refused`() {
        // The one vision family with a compiled export this app can feed; a picture goes
        // in through the export's own brackets, so the text template is the same one.
        val vl = PromptTemplates.forModel(
            "react-native-executorch-lfm2.5-VL-1.6B-lfm2_5_vl_1_6b_8da4w_xnnpack.pte",
        )
        assertThat(vl).isSameInstanceAs(PromptTemplates.forModel("lfm2.5.pte"))
        assertThat(vl?.vision?.side).isEqualTo(512)
        assertThat(vl?.vision?.pixels).isEqualTo(PixelRange.RAW)
        assertThat(PromptTemplates.forModel("Qwen3-VL-2B.pte")).isNull()
        assertThat(PromptTemplates.forModel("qwen3.pte")?.vision).isNull()
        // Gemma 3's official export: the processor's 896 square, normalised, 256 positions.
        val gemma = PromptTemplates.forModel("gemma-3-4b-it-HQQ-INT8-INT4.pte")?.vision
        assertThat(gemma?.side).isEqualTo(896)
        assertThat(gemma?.tokens).isEqualTo(256)
        assertThat(gemma?.pixels).isEqualTo(PixelRange.SIGNED)
        assertThat(gemma?.fit).isEqualTo(Fit.STRETCH)
        assertThat(vl?.vision?.fit).isEqualTo(Fit.LETTERBOX)
        assertThat(gemma?.before).endsWith("<start_of_image>")
        assertThat(gemma?.after).startsWith("<end_of_image>")
    }

    @Test
    fun `every advertised family is actually recognisable`() {
        // `known` feeds the error message that tells the user what this build can run;
        // advertising a family the matcher cannot reach would be a promise with no door.
        val installable = listOf(
            "qwen3.pte",
            "qwen2.5.pte",
            "smollm2.pte",
            "smollm3.pte",
            "llama-3.2.pte",
            "phi-4-mini.pte",
            "gemma-3.pte",
            "lfm2.5.pte",
            "lfm2.5-vl.pte",
        )
        assertThat(installable.mapNotNull { PromptTemplates.forModel(it) })
            .hasSize(PromptTemplates.known.size)
    }
}

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

/**
 * What can be read from a GGUF's filename.
 *
 * Filenames are the only thing available before a download, and the only thing shared
 * between a model and its projector. Publishers are consistent enough about the convention
 *: `<model>-<quantization>.gguf`, with projectors as `mmproj-<model>-<quantization>.gguf`
 *. That it carries real information, but it is a convention and not a guarantee, which is
 * why every rule here fails closed rather than guessing.
 */
object GgufFileName {

    /** True for a multimodal projector rather than a chat model. */
    fun isProjector(fileName: String): Boolean = fileName.startsWith(PROJECTOR_PREFIX, true)

    /**
     * The model's identity with its quantization suffix removed.
     *
     * `LFM2.5-VL-1.6B-Q4_K_M.gguf` becomes `LFM2.5-VL-1.6B`, which is the part a projector
     * published beside it also carries. Returns the whole stem when no suffix is
     * recognised, which pairs nothing rather than pairing wrongly.
     */
    fun modelIdentity(fileName: String): String {
        val stem = fileName.removeSuffix(GGUF_SUFFIX).removePrefix(PROJECTOR_PREFIX).trim('-')
        // `mmproj-F16.gguf` names its own precision and nothing else. Reporting "F16" as an
        // identity would let it match any model that happened to reduce to the same token,
        // so a name that is only a quantization is treated as naming no model at all.
        if (QUANTIZATION.matches(stem)) return ""
        val quantization = QUANTIZATION_SUFFIX.find(stem) ?: return stem
        return stem.substring(0, quantization.range.first)
    }

    /**
     * The quantization a file was published at, or null when the name does not say.
     *
     * The complement of [modelIdentity]: together they account for the whole stem. Read
     * from the name rather than the header because llama.cpp writes `general.file_type`
     * after the tokenizer, and reading past the tokenizer costs megabytes.
     */
    fun quantization(fileName: String): String? {
        val stem = fileName.removeSuffix(GGUF_SUFFIX).removePrefix(PROJECTOR_PREFIX).trim('-')
        if (QUANTIZATION.matches(stem)) return stem
        return QUANTIZATION_SUFFIX.find(stem)?.groupValues?.get(1)
    }

    /**
     * The name a projector is saved under locally.
     *
     * Derived from the model file rather than kept from the repository, because the two are
     * only loosely related upstream: publishers quantize the projector differently, spell
     * the model name with different capitalisation, and sometimes ship it as a bare
     * `mmproj-F16.gguf`. Renaming on the way in turns a guess at load time into a lookup.
     */
    fun projectorNameFor(modelFileName: String): String =
        "$PROJECTOR_PREFIX-${modelFileName.removeSuffix(GGUF_SUFFIX)}$GGUF_SUFFIX"

    const val GGUF_SUFFIX = ".gguf"
    private const val PROJECTOR_PREFIX = "mmproj"

    /** `Q4_K_M`, `F16`, `BF16`, `IQ3_XXS` and the rest of the GGUF quantization vocabulary. */
    private const val QUANTIZATION_PATTERN = """I?Q\d[\w_]*|B?F16|F32"""

    private val QUANTIZATION = Regex(QUANTIZATION_PATTERN, RegexOption.IGNORE_CASE)
    private val QUANTIZATION_SUFFIX =
        Regex("""-($QUANTIZATION_PATTERN)$""", RegexOption.IGNORE_CASE)
}

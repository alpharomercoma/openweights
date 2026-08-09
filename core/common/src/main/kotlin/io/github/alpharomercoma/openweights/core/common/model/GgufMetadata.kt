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
 * The parts of a GGUF header that determine whether a model fits on this phone.
 *
 * Read from the file itself rather than from the repo card, because the numbers that
 * matter are per-file and the card does not carry them.
 *
 * @param keyValueHeadsPerLayer attention key/value heads for each block. This is a list,
 *   not a single number, because hybrid architectures vary it per layer — LFM2 runs
 *   convolution in most blocks and attention in only a third of them, so treating it as
 *   uniform overstates the KV cache by roughly three times.
 */
data class GgufMetadata(
    val architecture: String,
    val blockCount: Int,
    val embeddingLength: Int,
    val headCount: Int,
    val keyValueHeadsPerLayer: List<Int>,
    val trainingContextLength: Int,
    val fileType: GgufFileType,
    val name: String?,
) {
    /**
     * Width of one attention head. GGUF may state it directly; otherwise it is the
     * embedding width divided across the heads.
     */
    val headDimension: Int
        get() = if (headCount > 0) embeddingLength / headCount else 0

    /** Total key/value heads across every block — what the KV cache is actually sized by. */
    val totalKeyValueHeads: Int get() = keyValueHeadsPerLayer.sum()

    /**
     * Bytes the KV cache occupies at [contextLength].
     *
     * Keys and values are each stored per head per token, at 16 bits by default.
     */
    fun kvCacheBytes(contextLength: Int): Long =
        2L * totalKeyValueHeads * headDimension * contextLength * KV_ELEMENT_BYTES

    private companion object {
        /** llama.cpp defaults to an f16 KV cache. */
        const val KV_ELEMENT_BYTES = 2
    }
}

/**
 * The quantization a GGUF file was written with.
 *
 * Values match llama.cpp's `llama_ftype`; unknown values keep their number so a model
 * quantized with something newer than this build still displays honestly.
 */
enum class GgufFileType(val id: Int, val label: String) {
    F32(0, "F32"),
    F16(1, "F16"),
    Q4_0(2, "Q4_0"),
    Q4_1(3, "Q4_1"),
    Q8_0(7, "Q8_0"),
    Q5_0(8, "Q5_0"),
    Q5_1(9, "Q5_1"),
    Q2_K(10, "Q2_K"),
    Q3_K_S(11, "Q3_K_S"),
    Q3_K_M(12, "Q3_K_M"),
    Q3_K_L(13, "Q3_K_L"),
    Q4_K_S(14, "Q4_K_S"),
    Q4_K_M(15, "Q4_K_M"),
    Q5_K_S(16, "Q5_K_S"),
    Q5_K_M(17, "Q5_K_M"),
    Q6_K(18, "Q6_K"),
    IQ2_XXS(19, "IQ2_XXS"),
    IQ2_XS(20, "IQ2_XS"),
    IQ3_XXS(23, "IQ3_XXS"),
    IQ1_S(24, "IQ1_S"),
    IQ4_NL(25, "IQ4_NL"),
    IQ3_S(26, "IQ3_S"),
    IQ2_S(28, "IQ2_S"),
    IQ4_XS(30, "IQ4_XS"),
    BF16(32, "BF16"),
    UNKNOWN(-1, "unknown"),
    ;

    companion object {
        fun fromId(id: Int): GgufFileType = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

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
 * Per-model generation settings. Saved alongside each model and editable by the user.
 *
 * Defaults follow llama.cpp's own defaults, which are reasonable for most instruction-tuned
 * models; individual model cards often recommend better values.
 */
data class SamplerParams(
    val temperature: Float = DEFAULT_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Float = DEFAULT_TOP_P,
    val minP: Float = DEFAULT_MIN_P,
    val repeatPenalty: Float = DEFAULT_REPEAT_PENALTY,
    val repeatLastN: Int = DEFAULT_REPEAT_LAST_N,
    /** `null` means "pick a new random seed for every generation". */
    val seed: Int? = null,
    /** 0 means "generate until the model stops or the context fills". */
    val maxTokens: Int = 0,
) {
    init {
        require(temperature >= 0f) { "temperature must be >= 0" }
        require(topP in 0f..1f) { "topP must be within 0..1" }
        require(minP in 0f..1f) { "minP must be within 0..1" }
        require(repeatPenalty > 0f) { "repeatPenalty must be > 0" }
        require(maxTokens >= 0) { "maxTokens must be >= 0" }
    }

    companion object {
        const val DEFAULT_TEMPERATURE = 0.8f
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_TOP_P = 0.95f
        const val DEFAULT_MIN_P = 0.05f
        const val DEFAULT_REPEAT_PENALTY = 1.1f
        const val DEFAULT_REPEAT_LAST_N = 64
    }
}

/**
 * How a model is loaded. Separate from [SamplerParams] because changing any of these
 * requires reloading the model, while sampler settings apply to the next generation.
 */
data class ModelLoadParams(
    /** KV cache size in tokens. Dominates memory use after the weights themselves. */
    val contextLength: Int = DEFAULT_CONTEXT_LENGTH,
    /**
     * Threads used to generate each token. `null` lets the engine choose.
     *
     * Generation is bandwidth-bound, so it peaks around the number of big cores and gets
     * *slower* when little cores join and everyone waits for them.
     */
    val threadCount: Int? = null,
    /**
     * Threads used to process the prompt. `null` lets the engine choose.
     *
     * Prompt processing is compute-bound and keeps scaling to every core on the phone,
     * which is why it is configured separately from [threadCount].
     */
    val batchThreadCount: Int? = null,
    /** Layers to offload to the GPU. 0 = CPU only, which is the fastest path on most phones. */
    val gpuLayers: Int = 0,
    /**
     * Memory-map the weights instead of reading them into the heap. Keeps resident memory
     * low and load times short; the kernel can evict pages under pressure.
     */
    val useMmap: Boolean = true,
) {
    init {
        require(contextLength > 0) { "contextLength must be > 0" }
        require(threadCount == null || threadCount > 0) { "threadCount must be > 0" }
        require(batchThreadCount == null || batchThreadCount > 0) {
            "batchThreadCount must be > 0"
        }
        require(gpuLayers >= 0) { "gpuLayers must be >= 0" }
    }

    companion object {
        const val DEFAULT_CONTEXT_LENGTH = 4096
    }
}

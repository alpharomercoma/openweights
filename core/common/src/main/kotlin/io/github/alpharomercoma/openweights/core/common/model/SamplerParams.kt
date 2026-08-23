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
    /** Whether the model is allowed to think before answering, where it can. */
    val thinking: Boolean = true,
    /** How much thinking, for the models whose template reads an effort level. */
    val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
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
 * How hard the model should think, for models whose chat template offers the choice.
 *
 * Reasoning models spend tokens before answering. On a phone that time is measured in
 * tens of seconds, so being able to turn it down is a throughput control as much as a
 * quality one.
 */
enum class ReasoningEffort(val wireName: String?, val label: String) {
    /** Leave the template's own default alone. */
    DEFAULT(null, "Default"),
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    ;

    companion object {
        fun fromName(name: String?): ReasoningEffort =
            entries.firstOrNull { it.name == name } ?: DEFAULT
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
     * Memory-map the weights instead of reading them into the heap.
     *
     * Off, which is the opposite of what this said and of what mmap is usually for. The
     * claim here was that mapping keeps resident memory low. Measured on the test phone
     * with a 1.48 GiB Q4_0 model, through `llama-bench` so nothing of this app is in the
     * number:
     *
     * | | peak RSS | private clean | private dirty |
     * | --- | --- | --- | --- |
     * | mapped | 3.24 GB | 1.47 GB | 1.62 GB |
     * | read | **1.95 GB** | 6.7 MB | 1.77 GB |
     *
     * The weights are resident twice when they are mapped. KleidiAI's kernels want Q4_0 in
     * a blocked layout, so the CPU backend repacks every accelerated tensor into a buffer
     * of its own, and the mapped pages it read them from stay resident behind it. Reading
     * instead of mapping pays for one copy.
     *
     * What it costs: about 245 ms on a warm load, measured over five runs each, 1,140 ms
     * mapped against 1,385 ms read. Throughput is the same to within noise, pp256 106.7
     * against 104.6 and tg64 24.1 against 23.8. The cold case could not be measured because
     * dropping the page cache needs root, and it should be close either way since the
     * repack faults in every page regardless.
     *
     * Through the app rather than the bench, over the same six turn conversation: peak PSS
     * 3.32 GB mapped against **2.06 GB** read, and decode 15.78 t/s against 16.15. Not
     * slower, 1.27 GB lighter, and 2.06 GB is what `FitEstimator` predicts for this model at
     * this window, so the estimate was right and mapping was the whole discrepancy.
     *
     * A quarter of a second against 1.27 GB is not a close call on a phone. It also
     * changes the failure mode on a small device from thrashing on evicted pages to
     * needing less memory in the first place.
     */
    val useMmap: Boolean = false,
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

        /**
         * The context window the app will let a user ask for.
         *
         * The floor is where a chat stops being able to hold a question and its answer.
         * The ceiling is not a model limit, it is a memory one: the KV cache grows with
         * this number and is what runs a phone out of RAM. Both screens that offer the
         * choice read them from here, so raising the ceiling is one edit rather than
         * three that have to agree.
         */
        const val MIN_CONTEXT_LENGTH = 1024
        const val MAX_CONTEXT_LENGTH = 32_768

        /** Slider stops between the two, giving roughly 1k granularity. */
        const val CONTEXT_STEPS = 30
    }
}

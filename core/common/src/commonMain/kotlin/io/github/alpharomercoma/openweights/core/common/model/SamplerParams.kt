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
    /**
     * The most tokens a thinking block may run to before the engine closes it.
     *
     * [NO_REASONING_BUDGET] leaves the block to the model. At the cap the engine writes
     * the template's own end-of-thinking tag into the reply and the model answers from
     * what it has, which is llama-server's `reasoning_budget_tokens` done on the phone.
     * Measured on the routing matrix with Qwen3-1.7B on 2026-09-02: 128 keeps the
     * unrestricted 31 of 33 while 64 drops to 29 and 32 to 27, so the tool pass uses 128
     * and prose keeps the block open. llama.cpp only; the ExecuTorch engine ignores it.
     */
    val reasoningBudget: Int = NO_REASONING_BUDGET,
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
        const val NO_REASONING_BUDGET = -1
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
     *
     * **Two corrections to that table (2026-09-04), neither of which changes the choice.**
     *
     * Reading is not one copy in physical memory, it is one copy *charged to this
     * process*. llama.cpp reads through buffered stdio, so the kernel also holds the whole
     * GGUF in page cache, and page cache is not counted in RSS or PSS — so part of the
     * 1.27 GB the read path looked lighter by is accounting rather than memory. Dropping
     * that copy with `posix_fadvise(POSIX_FADV_DONTNEED)` after the load was tried and
     * **does not work**: models live on emulated external storage, and on the test phone
     * the file stayed fully cached across a load (system `Cached` moved 4 MB, not 663).
     * It is a clean, reclaimable copy, which is the first thing the kernel drops under
     * pressure, so it is charged here as a caveat on the table rather than a problem.
     *
     * The second is why mapping would not help even so: the tensors the CPU actually
     * computes against are anonymous either way, because KleidiAI repacks into its own
     * buffer whichever mode the source came from. Mapping adds a clean copy on top; it
     * does not make the hot weights droppable. See docs/research/first-turn-latency.md.
     */
    val useMmap: Boolean = false,
    /**
     * Whether prompt reading may be handed to a GPU while generation stays where it is.
     *
     * The one mechanism that separates the two halves of a turn. A batch is only offloaded
     * when it is large enough to repay the transfer, and generation is always a batch of
     * one, so this reaches prefill and never decode — which is exactly the split worth
     * making, since the GPU reads a prompt faster than the CPU and writes an answer slower.
     *
     * With [gpuLayers] at zero it is the whole of "prefill on the GPU, decode on the CPU".
     * With every layer on the GPU both halves are already there and this changes nothing.
     * On by default, matching llama.cpp, and only reached at all on a device that has a
     * GPU backend to offload to.
     */
    val opOffload: Boolean = true,
    /**
     * The KV cache at eight bits, K and V both, with flash attention.
     *
     * Decode is affine in context: on the MT6991 it costs 3.42 microseconds per generated
     * token per token of context, which is the bytes of cache that attention reads back.
     * Q8_0 halves those bytes, so at a wide window it is worth on the order of a tenth of
     * the per-token time; what it costs is a little accuracy, and how much is a property of
     * the model. Gemma is known to be sensitive to it, and a four-bit K collapses outright
     * (llama.cpp discussion #23470), which is why this is a single switch to Q8_0 and not
     * a choice of types. Off until it has been measured on the phone against the models
     * this app recommends: `ContextLengthBenchmark` with `-e kv q8` is the measurement.
     */
    val kvCacheQuantized: Boolean = false,
    /**
     * Draft-free speculation on the llama.cpp decode loop.
     *
     * The next few tokens are proposed from n-grams already in the context and verified
     * by the model in one batch, so a span it is copying (a page being rebuilt, a quote,
     * a file written back with one change) decodes several tokens for the price of about
     * 1.7 single steps. Where the context has no match there is no draft and no cost.
     * Transformers only: a hybrid or recurrent cache cannot drop a rejected tail, and the
     * engine keeps the single path on those whatever this says. Off until measured on
     * the phone: `SpeculationBenchmark` is the measurement, and the go rule is in
     * docs/research/qa-sweep-2026-09-02.md.
     */
    val speculation: Boolean = false,
    /**
     * How many tokens one picture is turned into, or [AUTOMATIC_IMAGE_TOKENS] for whatever
     * the projector's own metadata asks for.
     *
     * Automatic in the app, and measured before it was left that way. libmtmd exposes a
     * floor and a ceiling on how much of an image the vision transformer is given, and this
     * sets both at once, which is what makes it a budget rather than a cap: a ceiling alone
     * leaves a small picture costing whatever the metadata's floor said, and a ceiling below
     * the model's own floor makes clip refuse the projector outright rather than clamp it.
     *
     * The reason it is not the app's speed control is that on the projector families this
     * app recommends, lowering it makes a turn *slower*. LFM2 tiles an image whose pixel
     * count is more than twice its budget, and each 512-pixel tile is a flat 256 tokens, so
     * asking for a smaller budget asks for more tiles. Measured on a Snapdragon 8 Gen 3
     * against a 461 by 1024 screenshot: automatic, 280 prompt tokens and 8.5 seconds; the
     * same picture at a budget of 128, 677 tokens and 32.8 seconds. What the app moves
     * instead is `ModelPreferences.imageTokens`, which sets the size of the picture the app sends.
     *
     * Kept, and exercised, because it is the knob for the other half of the trade and the
     * only way to reproduce that finding: see `ImageTokenBenchmark` and
     * `docs/research/image-tokens.md`.
     */
    val imageTokens: Int = AUTOMATIC_IMAGE_TOKENS,
    /**
     * How many prompt tokens the engine submits for evaluation at once.
     *
     * 512, which is what this was hard-coded to, and it is here rather than in the code so
     * it can be swept. Prefill is the compute-bound half of a turn and the one that
     * dominates a first answer, so batching more of it at a time is the obvious lever;
     * what it costs is the scratch buffer the graph allocates for intermediate
     * activations, which scales with [microBatchTokens] and is transient but real on a
     * phone that is already holding two gigabytes of weights.
     *
     * Not a user setting and not intended to become one. It is an engine constant whose
     * value should be whatever `BatchSizeBenchmark` says, on the same footing as
     * [kvCacheQuantized]: present, exercised, and moved only by a measurement.
     */
    val batchTokens: Int = DEFAULT_BATCH_TOKENS,
    /**
     * The physical batch: how much of [batchTokens] is computed in one graph.
     *
     * The one that actually sizes the scratch buffer, which is why it is separate. Raising
     * the logical batch without this changes how work is queued and not how it is done.
     */
    val microBatchTokens: Int = DEFAULT_BATCH_TOKENS,
) {
    init {
        require(contextLength > 0) { "contextLength must be > 0" }
        require(imageTokens >= 0) { "imageTokens must be >= 0" }
        require(batchTokens > 0) { "batchTokens must be > 0" }
        require(microBatchTokens > 0) { "microBatchTokens must be > 0" }
        // llama.cpp submits the logical batch in physical chunks, so a physical batch
        // larger than the logical one is a configuration that cannot mean anything.
        require(microBatchTokens <= batchTokens) {
            "microBatchTokens must be <= batchTokens"
        }
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

        /** Leaves the projector's own metadata in charge. See [imageTokens]. */
        const val AUTOMATIC_IMAGE_TOKENS = 0

        /**
         * The batch this engine has always used, kept until something measures otherwise.
         *
         * Both halves, because they were both 512 and moving one without the other is a
         * third configuration nobody has a question about.
         */
        const val DEFAULT_BATCH_TOKENS = 512

        /** The range `ImageTokenBenchmark` sweeps. See [imageTokens]. */
        const val MIN_IMAGE_TOKENS = 16
        const val MAX_IMAGE_TOKENS = 1024
    }
}

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

package io.github.alpharomercoma.openweights.core.engine

/**
 * Thin JNI surface over llama.cpp. Everything here maps one-to-one onto a native
 * function; policy and lifecycle live in [LlamaCppEngine].
 *
 * Native handles are raw pointers held as `Long`. Passing a stale handle is undefined
 * behaviour, so only [LlamaCppEngine] may touch them.
 */
@Suppress("TooManyFunctions")
internal class LlamaBridge {
    /** Receives each generated fragment. Returning false stops generation. */
    internal fun interface TokenSink {
        fun onToken(text: String): Boolean
    }

    /**
     * Receives the finished reply after llama.cpp has parsed it for this model's format.
     *
     * @param toolCalls flattened `[id, name, argumentsJson]` triples.
     */
    internal fun interface ReplySink {
        fun onReply(content: String, reasoning: String, toolCalls: Array<String>)
    }

    external fun nativeSystemInfo(): String

    /** Flattened `[id, description, type, totalMemoryBytes]` per compute device. */
    external fun nativeComputeDevices(): Array<String>

    /** @return an opaque session handle. Throws [LlamaException] if the model cannot load. */
    external fun nativeLoadModel(
        modelPath: String,
        mmprojPath: String?,
        contextLength: Int,
        threadCount: Int,
        batchThreadCount: Int,
        gpuLayers: Int,
        useMmap: Boolean,
        /**
         * Whether a large batch may be handed to a GPU even when the weights are not on it.
         *
         * The scheduler only offloads an operation when the batch is big enough to repay
         * the transfer, and generation is always a batch of one — so this separates the two
         * halves of a turn: prompt reading can run on the GPU while writing stays on the
         * CPU. It is the only mechanism either runtime has for that split.
         */
        opOffload: Boolean,
    ): Long

    external fun nativeFreeModel(handle: Long)

    /**
     * Prefills the fresh-conversation prefix and, on models that refuse rollback, keeps a
     * snapshot of it. Blocks like generation; cancellable via [nativeCancel].
     *
     * @return `[warmedTokens, reusedTokens, prefillMs, snapshotBytes]`; a cancelled warm
     * returns what it managed rather than throwing.
     */
    @Suppress("LongParameterList")
    external fun nativeWarm(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        toolNames: Array<String>,
        toolDescriptions: Array<String>,
        toolSchemas: Array<String>,
        enableThinking: Boolean,
        reasoningEffort: String?,
    ): LongArray?

    external fun nativeResetContext(handle: Long)

    external fun nativeSetThreads(handle: Long, threads: Int, batchThreads: Int)

    /** Safe to call while [nativeGenerate] is running on another thread. */
    external fun nativeCancel(handle: Long)

    /**
     * @return `[parameterCount, sizeBytes, contextSize, trainingContextSize, layerCount,
     * contextUsed]`.
     */
    external fun nativeModelInfo(handle: Long): LongArray

    external fun nativeModelDescription(handle: Long): String

    /**
     * Which backend holds the weights, as llama.cpp accounted for them at load.
     *
     * `"OpenCL|OpenCL:680|CPU:96"`, dominant backend first. Empty when nothing was
     * captured. This is the only honest answer to "did the GPU toggle do anything": the
     * layer count llama.cpp also reports is the number requested, not the number placed.
     */
    external fun nativeOffloadSummary(handle: Long): String

    /**
     * `[vision, audio, speech]`: everything the loaded projector can do.
     *
     * The first two are what it reads, the third is whether it can speak, which is a
     * generative decoder rather than the encoder behind the audio flag. All false without a
     * projector. One call rather than three because one file answers all of it.
     */
    external fun nativeMediaSupport(handle: Long): BooleanArray

    /** True when the loaded chat template understands being told whether to think. */
    external fun nativeSupportsThinking(handle: Long): Boolean

    external fun nativeSupportsTools(handle: Long): Boolean

    /** True when the chat template will also render what a tool gave back. */
    external fun nativeSupportsToolResults(handle: Long): Boolean

    /** True when the chat template renders `reasoning_effort` into the prompt. */
    external fun nativeSupportsReasoningEffort(handle: Long): Boolean

    /** The marker the projector expects where an attachment belongs in the prompt. */
    external fun nativeMediaMarker(handle: Long): String

    /**
     * Blocks until generation finishes, calling [sink] on the calling thread for each token.
     *
     * @return `[stopReasonOrdinal, promptTokens, generatedTokens, prefillMs, decodeMs,
     * timeToFirstTokenMs, contextUsed, contextSize, thinkingPrefilled, cachedTokens]`.
     */
    @Suppress("LongParameterList")
    external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        /** Empty for every message except a tool result, which names the call it answers. */
        toolCallIds: Array<String>,
        mediaPaths: Array<String>,
        mediaCounts: IntArray,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Int,
        maxTokens: Int,
        toolNames: Array<String>,
        toolDescriptions: Array<String>,
        toolSchemas: Array<String>,
        enableThinking: Boolean,
        reasoningEffort: String?,
        sink: TokenSink,
        replySink: ReplySink,
    ): LongArray?

    companion object {
        init {
            System.loadLibrary("openweights_llama")
        }
    }
}

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
        contextLength: Int,
        threadCount: Int,
        batchThreadCount: Int,
        gpuLayers: Int,
        useMmap: Boolean,
    ): Long

    external fun nativeFreeModel(handle: Long)

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
     * Blocks until generation finishes, calling [sink] on the calling thread for each token.
     *
     * @return `[stopReasonOrdinal, promptTokens, generatedTokens, prefillMs, decodeMs,
     * timeToFirstTokenMs, contextUsed, contextSize]`.
     */
    @Suppress("LongParameterList")
    external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
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
        sink: TokenSink,
        replySink: ReplySink,
    ): LongArray?

    companion object {
        init {
            System.loadLibrary("openweights_llama")
        }
    }
}

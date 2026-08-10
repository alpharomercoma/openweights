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

import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.MediaKind
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Thrown when the native engine fails to load a model or generate. */
class LlamaException(message: String) : RuntimeException(message)

/** Why a generation stopped. Ordinals must stay in sync with `StopReason` in the JNI layer. */
enum class StopReason {
    END_OF_TURN,
    MAX_TOKENS,
    CONTEXT_FULL,
    CANCELLED,
    ERROR,
}

/** Measured throughput for one generation. Nothing here is estimated. */
data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val prefillMs: Long,
    val decodeMs: Long,
    val timeToFirstTokenMs: Long,
    val contextUsed: Int,
    val contextSize: Int,
) {
    /** Prompt-processing throughput, or null when nothing needed decoding (full cache hit). */
    val prefillTokensPerSecond: Double?
        get() = if (prefillMs > 0 && promptTokens > 0) {
            promptTokens * MILLIS_PER_SECOND / prefillMs
        } else {
            null
        }

    /** Generation throughput. The number users actually feel. */
    val decodeTokensPerSecond: Double?
        get() = if (decodeMs > 0 && generatedTokens > 1) {
            (generatedTokens - 1) * MILLIS_PER_SECOND / decodeMs
        } else {
            null
        }
}

private const val MILLIS_PER_SECOND = 1000.0

/** What the engine emits while producing a reply. */
sealed interface GenerationEvent {
    /** A fragment of the reply. Fragments are not necessarily whole words. */
    data class Token(val text: String) : GenerationEvent

    /**
     * Terminal event carrying why generation stopped, how fast it ran, and the reply as
     * llama.cpp parsed it for this model's format.
     *
     * @param content the answer with reasoning and tool syntax removed.
     * @param reasoning the model's thinking, when the format separates it.
     * @param toolCalls what the model asked the app to run, if anything.
     */
    data class Completed(
        val reason: StopReason,
        val stats: GenerationStats,
        val content: String = "",
        val reasoning: String = "",
        val toolCalls: List<ToolCall> = emptyList(),
    ) : GenerationEvent
}

/**
 * What kinds of attachment the loaded model can read.
 *
 * All false for a text-only model, and for a multimodal model loaded without its
 * projector file. The UI uses this to decide what the attachment button offers, so an
 * unusable option is never shown rather than shown and then rejected.
 */
data class MediaSupport(val vision: Boolean = false, val audio: Boolean = false) {
    /**
     * True when a video can be sent to this model.
     *
     * Derived from [vision] rather than asked of the projector: libmtmd decodes video by
     * shelling out to an `ffmpeg` binary in PATH, which no Android app can provide, so the
     * app samples frames itself and sends them as images. Any model that can read a
     * picture can therefore read a video, at the cost of one prefill per frame.
     */
    val video: Boolean get() = vision

    val any: Boolean get() = vision || audio

    fun accepts(kind: MediaKind): Boolean = when (kind) {
        MediaKind.IMAGE -> vision
        MediaKind.AUDIO -> audio
        MediaKind.VIDEO -> video
        MediaKind.OTHER -> false
    }
}

/** Static facts about the model currently loaded. */
data class LoadedModelInfo(
    val description: String,
    val parameterCount: Long,
    val sizeBytes: Long,
    val contextSize: Int,
    val trainingContextSize: Int,
    val layerCount: Int,
    val contextUsed: Int,
    val mediaSupport: MediaSupport = MediaSupport(),
    /** True when this model's chat template understands being told whether to think. */
    val supportsThinking: Boolean = false,
    /**
     * True when this model's chat template renders tool definitions.
     *
     * A template that does not will drop them without complaint, and the model then
     * answers in prose. Knowing the difference is what stops the app offering a tool
     * setting on a model that cannot act on it.
     */
    val supportsTools: Boolean = false,
)

/**
 * Runs a language model on this device.
 *
 * llama.cpp is the only implementation today. The interface exists so a second backend
 * (ExecuTorch for NPU acceleration, for example) can be added without touching callers
 * see `docs/research/inference-engines.md` for why that is a live possibility.
 *
 * Implementations hold a single model at a time and are not safe for concurrent
 * generation; [cancel] is the exception and may be called while [chat] is running.
 */
interface InferenceEngine : AutoCloseable {
    /** The model currently loaded, or null. */
    val loadedModel: LoadedModelInfo?

    /**
     * Loads [modelFile], replacing any previously loaded model.
     *
     * @param projectorFile the model's `mmproj` GGUF, which is what makes it able to read
     * images, audio or video. Optional, and ignored by text-only models.
     */
    suspend fun load(
        modelFile: File,
        params: ModelLoadParams = ModelLoadParams(),
        projectorFile: File? = null,
    )

    /** Releases the model and its KV cache. Safe to call when nothing is loaded. */
    suspend fun unload()

    /**
     * Generates a reply to [messages], streaming it as [GenerationEvent]s.
     *
     * The flow is cold: collection starts the generation and cancelling the collector
     * stops it. Conversation prefixes already in the KV cache are reused automatically,
     * so a follow-up turn only pays for the new tokens.
     */
    fun chat(
        messages: List<ChatMessage>,
        params: SamplerParams = SamplerParams(),
        /**
         * Tools the model may call. The engine renders these into the loaded model's own
         * tool syntax, so the same definitions work across models.
         */
        tools: List<ToolDefinition> = emptyList(),
    ): Flow<GenerationEvent>

    /** Stops the running generation. Safe to call from any thread. */
    fun cancel()

    /** Clears the KV cache so the next [chat] starts from an empty context. */
    suspend fun resetContext()

    /**
     * Retunes how many threads the next generation uses.
     *
     * Exposed because the best count is not a property of the phone but of its current
     * temperature: a throttled device is faster with fewer threads, measurably so.
     */
    suspend fun setThreads(generateThreads: Int, batchThreads: Int)

    /** Description of the active ggml backends and detected CPU features. */
    fun systemInfo(): String

    /**
     * Compute devices this phone can run layers on.
     *
     * Always includes the CPU. GPU entries appear only when a GPU backend is compiled in
     * *and* this device supports it, so Settings can offer exactly the real choices
     * instead of a disabled toggle that explains nothing.
     */
    fun computeDevices(): List<ComputeDevice>

    /**
     * Releases the model and any thread the implementation owns.
     *
     * The app keeps one engine for its lifetime; this exists so tests and short-lived
     * engines do not leak.
     */
    override fun close()
}

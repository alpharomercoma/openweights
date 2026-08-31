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
import io.github.alpharomercoma.openweights.core.common.model.OutputModality
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
    /**
     * Whether the chat template opened a thinking block that this reply continued from.
     *
     * Needed to store a reply that will still match the KV cache on the next turn. A
     * template like LFM2.5's ends the assistant opener with `<think>`, so that tag is in the
     * prompt and never in the reply, and the stored history has to have it put back.
     *
     * Reported rather than guessed from the text because the text cannot tell you. A reply
     * that finished thinking has a closing tag to infer it from; a reply cut off mid-thought
     * has neither tag and looks exactly like a reply that never thought. Getting that one
     * case wrong left a turn in the history that no longer matched the cache, which on a
     * hybrid model costs a full re-prefill of the whole conversation, measured at eleven to
     * nineteen seconds on every turn after it.
     */
    val thinkingPrefilled: Boolean = false,
    /**
     * Tokens this turn's prompt reused from the KV cache rather than re-decoding.
     *
     * [promptTokens] above is already just the freshly-decoded remainder — a follow-up turn
     * in a running conversation only pays for what changed — so `cachedTokens + promptTokens`
     * is the conversation's full length as tokenized this turn, and [cacheHitRate] is what
     * fraction of that the cache answered for free. Zero on a turn with an attachment:
     * embeddings are never compared against the cache, so media always re-evaluates the
     * whole conversation from scratch.
     */
    val cachedTokens: Int = 0,
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

    /** This turn's prompt, cached and fresh tokens together. */
    val totalPromptTokens: Int get() = cachedTokens + promptTokens

    /**
     * What fraction of this turn's prompt the KV cache answered for free.
     *
     * A conversation's first turn is a real, honest 0%: there was nothing in the cache yet
     * to match against, and that is exactly what a full miss is. Null only guards the
     * degenerate case of no prompt at all, which nothing sends in practice.
     */
    val cacheHitRate: Double?
        get() = totalPromptTokens.takeIf { it > 0 }?.let { cachedTokens.toDouble() / it }

    /**
     * These stats and [next]'s as one measurement, for a reply produced in several passes.
     *
     * A turn with a tool in it is two or more generations, and keeping only the last one's
     * numbers made the reply's row lie: a first pass that re-read the whole conversation
     * reported nothing, because the pass that happened to finish the turn was the one
     * written down. Token counts and times add; the rates are computed from the sums, so
     * they come out as the honest whole-turn throughput. What describes a moment rather
     * than a total — where the context stands, whether thinking was prefilled — is taken
     * from [next], the pass that ended the turn. Time to first token stays this side's:
     * the wait the user felt ended when the first pass started writing.
     */
    fun through(next: GenerationStats): GenerationStats = next.copy(
        promptTokens = promptTokens + next.promptTokens,
        generatedTokens = generatedTokens + next.generatedTokens,
        prefillMs = prefillMs + next.prefillMs,
        decodeMs = decodeMs + next.decodeMs,
        cachedTokens = cachedTokens + next.cachedTokens,
        timeToFirstTokenMs = timeToFirstTokenMs,
    )
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
    /**
     * The backend that actually holds the weights, as llama.cpp accounted for them.
     *
     * "CPU", "OpenCL", "Metal". Empty when the engine could not say. This is the answer to
     * "did asking for the GPU do anything", and it is not the same question as which
     * backends are registered or how many layers were requested: a GPU that fails to
     * attach loads the whole model onto the CPU and reports the requested layer count
     * regardless. Reading which buffers the tensors landed in is the only way to know.
     */
    val offloadedTo: String = "",
    /** Every buffer holding weights, largest first: `[OpenCL to 680, CPU to 96]` in MiB. */
    val offloadBuffers: List<Pair<String, Int>> = emptyList(),
    val mediaSupport: MediaSupport = MediaSupport(),
    /**
     * What this model emits, which decides which sampler settings reach it.
     *
     * [MediaSupport] is the inbound half of the same question. A model can read pictures
     * and write words, or read words and speak, and the two are answered by different
     * encoders in the same projector file.
     */
    val outputModality: OutputModality = OutputModality.TEXT,
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
    /**
     * True when this model's chat template will also render what a tool gave back.
     *
     * Deliberately not the same question as [supportsTools], because the two answers
     * differ on models this app ships against. Gemma's templates describe tools and then
     * require the roles to alternate strictly user then assistant, so the message carrying
     * the result raises rather than renders; FunctionGemma is tuned for calling and still
     * cannot be told what came back. A turn that calls a tool on one of those used to end
     * with the tool having run and nothing written, every time.
     */
    val supportsToolResults: Boolean = false,
    /**
     * True when the chat template does something with `reasoning_effort`.
     *
     * Measured by rendering the template twice with different values and comparing the
     * result, because no template declares this. A template that ignores the argument
     * produces identical prompts, and a control that provably changes nothing should not
     * be on screen.
     */
    val supportsReasoningEffort: Boolean = false,
    /** Absolute path of the weights currently owned by the engine. */
    val modelPath: String = "",
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
/** What warming the conversation prefix did. See [InferenceEngine.warm]. */
data class WarmResult(
    /** Tokens freshly decoded into the cache by this warm. */
    val warmedTokens: Int,
    /** Tokens the cache already held, so nothing was done for them. */
    val reusedTokens: Int,
    val prefillMs: Long,
    /** Size of the snapshot kept for models that refuse rollback, or 0. */
    val snapshotBytes: Long,
)

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

    /**
     * Prefills the head a fresh conversation will start with, so the first question does
     * not pay for it.
     *
     * [messages] is that head — normally one system message — and [tools] whatever the
     * first turn would offer; both must be composed exactly as the first turn will compose
     * them, because the engine reuses the work by comparing bytes, not intent. Runs on the
     * same serialized path as [chat] and honours [cancel]; a cancelled warm keeps the
     * progress it can and is not an error.
     *
     * Default is a quiet no-op: an engine that cannot warm — none is loaded, or the
     * runtime has no such path — reports null rather than failing, because warming is an
     * optimization and never an obligation.
     */
    suspend fun warm(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        /** Only [SamplerParams.thinking] and [SamplerParams.reasoningEffort] shape the prefix. */
        params: SamplerParams = SamplerParams(),
    ): WarmResult? = null

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

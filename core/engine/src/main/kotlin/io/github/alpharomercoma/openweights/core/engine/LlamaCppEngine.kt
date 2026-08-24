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

import android.util.Log
import io.github.alpharomercoma.openweights.core.common.device.CpuTopology
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.OutputModality
import io.github.alpharomercoma.openweights.core.common.model.ParsedToolCalls
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolCallParser
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * llama.cpp-backed [InferenceEngine].
 *
 * All native calls run on one dedicated thread. That is not just tidiness: llama.cpp
 * contexts are not thread-safe, and keeping generation on a single non-UI thread means the
 * JNIEnv handed to `nativeGenerate` stays valid for its token callbacks.
 */
class LlamaCppEngine internal constructor(
    private val bridge: LlamaBridge,
    private val defaultThreadCount: Int,
    private val defaultBatchThreadCount: Int,
) : InferenceEngine {
    constructor() : this(LlamaBridge(), recommendedThreadCount(), recommendedBatchThreadCount())

    private val engineExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "openweights-inference").apply { isDaemon = true }
        }

    private val engineThread: CoroutineDispatcher = engineExecutor.asCoroutineDispatcher()

    /** Guards load/unload against a concurrent generation. */
    private val lifecycleLock = Mutex()

    /**
     * Held while the native handle is used or freed.
     *
     * [cancel] runs on whatever thread the caller is on, so without this it could read a
     * handle that [freeCurrentHandle] deletes a moment later and hand a dangling pointer
     * to the native layer. Cancelling only flips an atomic flag natively, so holding a
     * lock across it costs nothing.
     */
    private val handleLock = Any()

    /** 0 when no model is loaded. Guarded by [handleLock] for cross-thread reads. */
    private val handle = AtomicLong(0)

    @Volatile
    private var currentModel: LoadedModelInfo? = null

    override val loadedModel: LoadedModelInfo? get() = currentModel

    override suspend fun load(modelFile: File, params: ModelLoadParams, projectorFile: File?) {
        require(modelFile.isFile) { "model file does not exist: ${modelFile.path}" }
        require(projectorFile == null || projectorFile.isFile) {
            "projector file does not exist: ${projectorFile?.path}"
        }
        lifecycleLock.withLock {
            withContext(engineThread) {
                freeCurrentHandle()
                val newHandle = bridge.nativeLoadModel(
                    modelPath = modelFile.absolutePath,
                    mmprojPath = projectorFile?.absolutePath,
                    contextLength = params.contextLength,
                    threadCount = params.threadCount ?: defaultThreadCount,
                    batchThreadCount = params.batchThreadCount ?: defaultBatchThreadCount,
                    gpuLayers = params.gpuLayers,
                    useMmap = params.useMmap,
                )
                val info = try {
                    readModelInfo(newHandle, modelFile.absolutePath)
                } catch (failure: Throwable) {
                    // The weights are already mapped at this point. Publishing the handle
                    // before interrogating it left a failed load resident and made a retry
                    // compound the memory pressure that commonly caused the failure.
                    bridge.nativeFreeModel(newHandle)
                    throw failure
                }
                synchronized(handleLock) {
                    handle.set(newHandle)
                    currentModel = info
                }
            }
        }
    }

    override suspend fun unload() {
        lifecycleLock.withLock {
            withContext(engineThread) { freeCurrentHandle() }
        }
    }

    override fun chat(
        messages: List<ChatMessage>,
        params: SamplerParams,
        tools: List<ToolDefinition>,
    ): Flow<GenerationEvent> = callbackFlow {
        val activeHandle = handle.get()
        check(activeHandle != 0L) { "no model is loaded" }
        require(messages.isNotEmpty()) { "cannot generate a reply to an empty conversation" }

        var parsed = ParsedReply()
        val prompt = renderPrompt(activeHandle, messages)

        // Registered before the blocking JNI call. During prefill there are no token
        // callbacks, so waiting for trySend to fail cannot observe a collector that left.
        // Closing the producer channel can happen from the cancelling thread and flips the
        // native atomic immediately.
        channel.invokeOnClose { cause ->
            if (cause != null) this@LlamaCppEngine.cancel()
        }

        val stats = bridge.nativeGenerate(
            handle = activeHandle,
            roles = messages.map { it.role.wireName }.toTypedArray(),
            contents = prompt.contents,
            toolCallIds = messages.map { it.toolCallId }.toTypedArray(),
            mediaPaths = prompt.mediaPaths,
            mediaCounts = prompt.mediaCounts,
            temperature = params.temperature,
            topK = params.topK,
            topP = params.topP,
            minP = params.minP,
            repeatPenalty = params.repeatPenalty,
            repeatLastN = params.repeatLastN,
            seed = params.seed ?: Random.nextInt(),
            maxTokens = params.maxTokens,
            toolNames = tools.map { it.name }.toTypedArray(),
            toolDescriptions = tools.map { it.description }.toTypedArray(),
            toolSchemas = tools.map { it.parametersJson }.toTypedArray(),
            enableThinking = params.thinking,
            reasoningEffort = params.reasoningEffort.wireName,
            sink = { text ->
                // trySend fails once the collector is gone, which stops generation.
                trySend(GenerationEvent.Token(text)).isSuccess
            },
            replySink = { content, reasoning, calls ->
                // llama.cpp knows several tool formats; where it recognises none, the
                // Kotlin parser covers the ones it does not, such as LFM2's.
                val nativeCalls = calls.toToolCalls()
                val fallback = if (nativeCalls.isEmpty()) {
                    ToolCallParser.parse(content)
                } else {
                    ParsedToolCalls(content, nativeCalls)
                }
                parsed = ParsedReply(fallback.text, reasoning, fallback.calls)
            },
        )

        if (stats != null) {
            send(completionEvent(stats, parsed))
            currentModel = currentModel?.copy(contextUsed = stats[CONTEXT_USED].toInt())
        }
        close()
    }
        // Tokens arrive faster than a Compose collector redraws. Without an unbounded
        // buffer, trySend fails on backpressure and the sink reads that as "the
        // collector left", silently truncating the reply. With it, a failed trySend
        // means the channel is closed, which is the only case that should stop us.
        .buffer(Channel.UNLIMITED)
        .flowOn(engineThread)

    override fun cancel() = synchronized(handleLock) {
        val activeHandle = handle.get()
        if (activeHandle != 0L) {
            bridge.nativeCancel(activeHandle)
        }
    }

    override suspend fun resetContext() {
        withContext(engineThread) {
            val activeHandle = handle.get()
            if (activeHandle != 0L) {
                bridge.nativeResetContext(activeHandle)
                currentModel = currentModel?.copy(contextUsed = 0)
            }
        }
    }

    /**
     * Frees the model and shuts down the engine thread.
     *
     * The application holds one engine for its whole lifetime, so this exists for tests
     * and for anything that creates a short-lived engine: without it each instance leaks
     * a thread.
     */
    override fun close() {
        // Generation runs on engineExecutor and does not hold handleLock while it is in
        // the native call, so freeing here directly could delete a session mid-decode.
        // Cancelling first and then freeing *on that thread* guarantees the native call
        // has returned before the memory goes away.
        cancel()
        runCatching {
            engineExecutor.submit { freeCurrentHandle() }.get(SHUTDOWN_TIMEOUT_SECONDS, SECONDS)
        }
        engineExecutor.shutdown()
    }

    override suspend fun setThreads(generateThreads: Int, batchThreads: Int) {
        require(generateThreads > 0 && batchThreads > 0) {
            "thread counts must be positive, got $generateThreads and $batchThreads"
        }
        withContext(engineThread) {
            val activeHandle = handle.get()
            if (activeHandle != 0L) {
                bridge.nativeSetThreads(activeHandle, generateThreads, batchThreads)
            }
        }
    }

    override fun systemInfo(): String = bridge.nativeSystemInfo()

    override fun computeDevices(): List<ComputeDevice> =
        ComputeDevice.parse(bridge.nativeComputeDevices())

    private fun freeCurrentHandle() = synchronized(handleLock) {
        val previous = handle.getAndSet(0)
        if (previous != 0L) {
            bridge.nativeFreeModel(previous)
        }
        currentModel = null
    }

    /** The terminal event, read out of the flat array the native layer returns. */
    private fun completionEvent(stats: LongArray, parsed: ParsedReply) = GenerationEvent.Completed(
        reason = STOP_REASONS.getOrElse(stats[0].toInt()) { StopReason.ERROR },
        content = parsed.content,
        reasoning = parsed.reasoning,
        toolCalls = parsed.toolCalls,
        stats = GenerationStats(
            promptTokens = stats[1].toInt(),
            generatedTokens = stats[2].toInt(),
            prefillMs = stats[3],
            decodeMs = stats[4],
            timeToFirstTokenMs = stats[5],
            contextUsed = stats[CONTEXT_USED].toInt(),
            contextSize = stats[7].toInt(),
            thinkingPrefilled = stats.getOrElse(THINKING_PREFILLED) { 0L } == TRUE_FLAG,
        ),
    )

    /** The conversation as arrays the native layer can take, media markers included. */
    private class RenderedPrompt(
        val contents: Array<String>,
        val mediaPaths: Array<String>,
        val mediaCounts: IntArray,
    )

    /**
     * Prepares the conversation for the native layer.
     *
     * The projector needs its own marker in the text wherever an attachment belongs, and
     * only it knows what that marker is. It varies per model. Without a projector loaded,
     * attachments are dropped rather than described: a text-only model handed a marker
     * would read it as literal text and answer about a picture it cannot see.
     */
    private fun renderPrompt(activeHandle: Long, messages: List<ChatMessage>): RenderedPrompt {
        val marker = if (currentModel?.mediaSupport?.any == true) {
            bridge.nativeMediaMarker(activeHandle)
        } else {
            null
        }
        val files = messages.map { if (marker == null) emptyList() else it.files }

        return RenderedPrompt(
            contents = messages.mapIndexed { index, message ->
                if (marker == null || files[index].isEmpty()) {
                    message.text
                } else {
                    message.withMediaMarkers(marker)
                }
            }.toTypedArray(),
            mediaPaths = files.flatten().map { it.path }.toTypedArray(),
            mediaCounts = files.map { it.size }.toIntArray(),
        )
    }

    private fun readModelInfo(activeHandle: Long, modelPath: String): LoadedModelInfo {
        val info = bridge.nativeModelInfo(activeHandle)
        // Read once: llama.cpp accounted for these at load and nothing moves afterwards.
        val offload = runCatching { bridge.nativeOffloadSummary(activeHandle) }.getOrDefault("")
        // `[vision, audio, speech]`. Read once, for the same reason: the projector is fixed
        // at load, and two of these three used to cost a JNI crossing each.
        val projector = bridge.nativeMediaSupport(activeHandle)
        return LoadedModelInfo(
            description = bridge.nativeModelDescription(activeHandle),
            parameterCount = info[0],
            sizeBytes = info[1],
            contextSize = info[2].toInt(),
            trainingContextSize = info[3].toInt(),
            layerCount = info[4].toInt(),
            contextUsed = info[5].toInt(),
            offloadedTo = offload.substringBefore('|'),
            offloadBuffers = offload.substringAfter('|', "")
                .split('|')
                .filter { it.isNotBlank() }
                .mapNotNull { entry ->
                    val name = entry.substringBefore(':')
                    val mib = entry.substringAfter(':', "").toIntOrNull() ?: return@mapNotNull null
                    name to mib
                },
            mediaSupport = MediaSupport(vision = projector[0], audio = projector[1]),
            outputModality = if (projector[2]) OutputModality.SPEECH else OutputModality.TEXT,
            supportsThinking = bridge.nativeSupportsThinking(activeHandle),
            supportsTools = bridge.nativeSupportsTools(activeHandle),
            supportsToolResults = bridge.nativeSupportsToolResults(activeHandle),
            supportsReasoningEffort = bridge.nativeSupportsReasoningEffort(activeHandle),
            modelPath = modelPath,
        ).also {
            // What the chat template can actually do, asked of the template at load. Logged
            // because every "why did it not search" question starts here and the answer is
            // otherwise invisible from outside the process.
            Log.i(
                "OpenWeights",
                "loaded: tools=${it.supportsTools} results=${it.supportsToolResults} " +
                    "thinking=${it.supportsThinking} " +
                    "effort=${it.supportsReasoningEffort} " +
                    "vision=${it.mediaSupport.vision} audio=${it.mediaSupport.audio} " +
                    "emits=${it.outputModality.name.lowercase()} " +
                    "ctx=${it.contextSize}",
            )
        }
    }

    /** The reply as the native parser produced it, filled in before the flow completes. */
    private data class ParsedReply(
        val content: String = "",
        val reasoning: String = "",
        val toolCalls: List<ToolCall> = emptyList(),
    )

    private companion object {
        /** Tool calls cross JNI flattened as [id, name, argumentsJson]. */
        const val TOOL_CALL_FIELDS = 3

        /** Index of the context-used field in the stats array the native layer returns. */
        const val CONTEXT_USED = 6

        /** Index and native truth value for whether the thinking prefix was reused. */
        const val THINKING_PREFILLED = 8
        const val TRUE_FLAG = 1L

        /** Long enough for a cancelled decode step to return; short enough not to hang. */
        const val SHUTDOWN_TIMEOUT_SECONDS = 10L

        val STOP_REASONS = StopReason.entries

        fun Array<String>.toToolCalls(): List<ToolCall> = toList().chunked(TOOL_CALL_FIELDS)
            .filter { it.size == TOOL_CALL_FIELDS }
            .map { ToolCall(id = it[0], name = it[1], argumentsJson = it[2]) }

        const val MIN_THREADS = 2
        const val MAX_GEN_THREADS = 6
        const val MAX_BATCH_THREADS = 8

        /**
         * Token generation is bandwidth-bound and gets *slower* once the little cores join
         * in, because every step waits for the slowest thread. Half the cores approximates
         * the big-core count on the phones we have measured.
         *
         * On a Dimensity MT6991 (8 cores) this picks 4, which measured fastest: 16.8 tok/s
         * versus 12.8 at 8 threads.
         */
        fun recommendedThreadCount(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(MIN_THREADS, MAX_GEN_THREADS)

        /**
         * Prompt processing is compute-bound and scales with cores, but only with cores of
         * the same speed. On the MT6991, where all eight are, 8 threads gave 69.5 tok/s
         * against 55.3 at 4. On an SM8650, where two of the eight are A520s, 8 threads gave
         * 105.2 t/s against 139.0 at 6: the barrier at the end of each operation waits for
         * the slowest thread, and a slice on a little core takes about twice as long.
         *
         * [CpuTopology] is what tells the two chips apart, and it answers "every core" for
         * the first one, so this is the same number it always was wherever it was right.
         */
        fun recommendedBatchThreadCount(): Int =
            CpuTopology.performanceCores().coerceIn(MIN_THREADS, MAX_BATCH_THREADS)
    }
}

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
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
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

    override suspend fun load(modelFile: File, params: ModelLoadParams) {
        require(modelFile.isFile) { "model file does not exist: ${modelFile.path}" }
        lifecycleLock.withLock {
            withContext(engineThread) {
                freeCurrentHandle()
                val newHandle = bridge.nativeLoadModel(
                    modelPath = modelFile.absolutePath,
                    contextLength = params.contextLength,
                    threadCount = params.threadCount ?: defaultThreadCount,
                    batchThreadCount = params.batchThreadCount ?: defaultBatchThreadCount,
                    gpuLayers = params.gpuLayers,
                    useMmap = params.useMmap,
                )
                handle.set(newHandle)
                currentModel = readModelInfo(newHandle)
            }
        }
    }

    override suspend fun unload() {
        lifecycleLock.withLock {
            withContext(engineThread) { freeCurrentHandle() }
        }
    }

    override fun chat(messages: List<ChatMessage>, params: SamplerParams): Flow<GenerationEvent> =
        callbackFlow {
            val activeHandle = handle.get()
            check(activeHandle != 0L) { "no model is loaded" }
            require(messages.isNotEmpty()) { "cannot generate a reply to an empty conversation" }

            val stats = bridge.nativeGenerate(
                handle = activeHandle,
                roles = messages.map { it.role.wireName }.toTypedArray(),
                contents = messages.map { it.text }.toTypedArray(),
                temperature = params.temperature,
                topK = params.topK,
                topP = params.topP,
                minP = params.minP,
                repeatPenalty = params.repeatPenalty,
                repeatLastN = params.repeatLastN,
                seed = params.seed ?: Random.nextInt(),
                maxTokens = params.maxTokens,
                sink = { text ->
                    // trySend fails once the collector is gone, which stops generation.
                    trySend(GenerationEvent.Token(text)).isSuccess
                },
            )

            if (stats != null) {
                send(
                    GenerationEvent.Completed(
                        reason = STOP_REASONS.getOrElse(stats[0].toInt()) { StopReason.ERROR },
                        stats = GenerationStats(
                            promptTokens = stats[1].toInt(),
                            generatedTokens = stats[2].toInt(),
                            prefillMs = stats[3],
                            decodeMs = stats[4],
                            timeToFirstTokenMs = stats[5],
                            contextUsed = stats[6].toInt(),
                            contextSize = stats[7].toInt(),
                        ),
                    ),
                )
                currentModel = currentModel?.copy(contextUsed = stats[6].toInt())
            }
            close()
            // nativeGenerate has already returned by this point, but a collector that walks
            // away mid-stream is handled inside the sink above: trySend fails and the native
            // loop stops. This is the belt-and-braces path for any other unwind.
            awaitClose { this@LlamaCppEngine.cancel() }
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
        freeCurrentHandle()
        engineExecutor.shutdown()
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

    private fun readModelInfo(activeHandle: Long): LoadedModelInfo {
        val info = bridge.nativeModelInfo(activeHandle)
        return LoadedModelInfo(
            description = bridge.nativeModelDescription(activeHandle),
            parameterCount = info[0],
            sizeBytes = info[1],
            contextSize = info[2].toInt(),
            trainingContextSize = info[3].toInt(),
            layerCount = info[4].toInt(),
            contextUsed = info[5].toInt(),
        )
    }

    private companion object {
        val STOP_REASONS = StopReason.entries

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
         * Prompt processing is compute-bound and keeps scaling to every core — on the same
         * device, 8 threads gave 69.5 tok/s prefill versus 55.3 at 4.
         */
        fun recommendedBatchThreadCount(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(MIN_THREADS, MAX_BATCH_THREADS)
    }
}

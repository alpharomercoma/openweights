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

package io.github.alpharomercoma.openweights.ui.chat

import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.engine.ComputeDevice
import io.github.alpharomercoma.openweights.core.engine.ComputeDeviceKind
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.GenerationStats
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaException
import io.github.alpharomercoma.openweights.core.engine.LoadedModelInfo
import io.github.alpharomercoma.openweights.core.engine.StopReason
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * An engine that answers instantly, or not at all until told to.
 *
 * The real one needs a gigabyte of weights and several seconds a reply, which makes it
 * useless for testing the things around it. This one exists so the view model's behaviour
 * can be checked: what happens on stop, on delete, on switching model mid conversation.
 */
class FakeInferenceEngine : InferenceEngine {
    /** When true, a generation waits for [emit] and [finish] instead of completing at once. */
    var hold = false

    /** Set before a load to make it throw, standing in for a corrupt or missing file. */
    var failNextLoad = false

    var cancelCount = 0
        private set

    var resetCount = 0
        private set

    /** Every conversation the engine was asked to answer, in order. */
    val prompts = mutableListOf<List<ChatMessage>>()

    private var loaded: LoadedModelInfo? = null
    private var tokens = Channel<String>(Channel.UNLIMITED)

    override val loadedModel: LoadedModelInfo? get() = loaded

    override suspend fun load(modelFile: File, params: ModelLoadParams, projectorFile: File?) {
        if (failNextLoad) {
            failNextLoad = false
            loaded = null
            throw LlamaException("could not load ${modelFile.name}")
        }
        loaded = LoadedModelInfo(
            description = "fake ${modelFile.nameWithoutExtension}",
            parameterCount = 1_000_000,
            sizeBytes = modelFile.length(),
            contextSize = params.contextLength,
            trainingContextSize = params.contextLength,
            layerCount = 1,
            contextUsed = 0,
        )
    }

    override suspend fun unload() {
        loaded = null
    }

    override fun chat(
        messages: List<ChatMessage>,
        params: SamplerParams,
        tools: List<ToolDefinition>,
    ): Flow<GenerationEvent> {
        prompts += messages
        if (!hold) {
            return flow {
                emit(GenerationEvent.Token(REPLY))
                emit(GenerationEvent.Completed(StopReason.END_OF_TURN, stats(), REPLY))
            }
        }
        tokens = Channel(Channel.UNLIMITED)
        return channelFlow {
            for (piece in tokens) {
                send(GenerationEvent.Token(piece))
            }
        }
    }

    /** Pushes one piece into a held generation. */
    fun emit(text: String) {
        tokens.trySend(text)
    }

    override fun cancel() {
        cancelCount++
        tokens.close()
    }

    override suspend fun resetContext() {
        resetCount++
    }

    override suspend fun setThreads(generateThreads: Int, batchThreads: Int) = Unit

    override fun systemInfo(): String = "fake engine"

    override fun computeDevices(): List<ComputeDevice> = listOf(
        ComputeDevice("cpu", "Fake CPU", ComputeDeviceKind.CPU, 0),
    )

    override fun close() {
        loaded = null
    }

    private fun stats() = GenerationStats(
        promptTokens = 4,
        generatedTokens = 4,
        prefillMs = 1,
        decodeMs = 1,
        timeToFirstTokenMs = 1,
        contextUsed = 8,
        contextSize = loaded?.contextSize ?: 0,
    )

    private companion object {
        const val REPLY = "A short answer."
    }
}

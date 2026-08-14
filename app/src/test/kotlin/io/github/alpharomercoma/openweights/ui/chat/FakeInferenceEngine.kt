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
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.engine.ComputeDevice
import io.github.alpharomercoma.openweights.core.engine.ComputeDeviceKind
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.GenerationStats
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaException
import io.github.alpharomercoma.openweights.core.engine.LoadedModelInfo
import io.github.alpharomercoma.openweights.core.engine.MediaSupport
import io.github.alpharomercoma.openweights.core.engine.StopReason
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * One pass of a turn, as the engine would hand it back.
 *
 * [content] is what llama.cpp's parser leaves once it has lifted a call and any thinking
 * out of the stream, which is not the same string as [text] whenever it recognised
 * something. [toolCalls] is what it recognised.
 */
data class ScriptedPass(
    val text: String,
    val content: String = text,
    val toolCalls: List<ToolCall> = emptyList(),
    val reason: StopReason = StopReason.END_OF_TURN,
)

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

    /** What the template test would report for this model. */
    var supportsThinking = true

    /** Off by default, so a test only deals with the tool loop when it asks to. */
    var supportsTools = false

    /** Set before a load to make it throw, standing in for a corrupt or missing file. */
    var failNextLoad = false

    /**
     * What each completion reports the context as holding.
     *
     * Settable because context pressure is what decides when the app folds a conversation,
     * and a fake that always reports eight tokens can never reach the threshold. Read
     * against [LoadedModelInfo.contextSize] after a load to express it as a fraction.
     */
    var contextUsed = 8

    var cancelCount = 0
        private set

    var resetCount = 0
        private set

    /** Every conversation the engine was asked to answer, in order. */
    val prompts = mutableListOf<List<ChatMessage>>()

    /**
     * The tool definitions offered on each call, in order.
     *
     * A turn decides pass by pass whether to show the model its tools, and "were tools
     * offered here" is the question behind most of what the loop does, so it is recorded
     * rather than inferred from what came back.
     */
    val offered = mutableListOf<List<ToolDefinition>>()

    /**
     * Replies to hand back, one per call, before falling back to a plain answer.
     *
     * A tool loop is several passes of one turn, and each has to be able to say something
     * different: the first asks for a tool, the last answers. Holding and emitting by hand
     * cannot express that, because the loop calls the engine again on its own.
     */
    val scripted = ArrayDeque<ScriptedPass>()

    /** What each loaded model claims it can read, keyed by file name. */
    val mediaSupport = mutableMapOf<String, MediaSupport>()

    /** Load order, so a test can prove which model the engine ended up holding. */
    val loads = mutableListOf<String>()

    /** Loads wait on this many milliseconds of virtual time, to overlap two of them. */
    var loadDelayMs = 0L

    private var loaded: LoadedModelInfo? = null
    private var events = Channel<GenerationEvent>(Channel.UNLIMITED)

    // Carries the same reading the stats do, because the real engine updates its loaded
    // model after every generation and the turn loop sizes what it sends from that.
    override val loadedModel: LoadedModelInfo? get() = loaded?.copy(contextUsed = contextUsed)

    override suspend fun load(modelFile: File, params: ModelLoadParams, projectorFile: File?) {
        if (loadDelayMs > 0) delay(loadDelayMs)
        loads += modelFile.name
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
            mediaSupport = mediaSupport[modelFile.name] ?: MediaSupport(),
            // A template that branches on the flag, which is what the load time test can
            // establish. Whether the weights honour it is a separate question and the
            // reason the view model keeps watching after this point.
            supportsThinking = supportsThinking,
            supportsTools = supportsTools,
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
        offered += tools
        if (!hold) {
            val pass = scripted.removeFirstOrNull() ?: ScriptedPass(REPLY)
            return flow {
                emit(GenerationEvent.Token(pass.text))
                emit(
                    GenerationEvent.Completed(
                        reason = pass.reason,
                        stats = stats(),
                        content = pass.content,
                        toolCalls = pass.toolCalls,
                    ),
                )
            }
        }
        events = Channel(Channel.UNLIMITED)
        return channelFlow {
            for (event in events) {
                send(event)
            }
        }
    }

    /** Pushes one piece into a held generation. */
    fun emit(text: String) {
        events.trySend(GenerationEvent.Token(text))
    }

    /**
     * Ends a held generation the way the model reaching its stop token does.
     *
     * [content] and [reasoning] stand in for what llama.cpp's parser hands back: the answer
     * with reasoning and tool syntax removed, and the thinking on its own. Both empty means
     * the parser recognised nothing of the format and the caller's buffer is all there is.
     */
    fun finish(content: String = "", reasoning: String = "") {
        events.trySend(
            GenerationEvent.Completed(
                reason = StopReason.END_OF_TURN,
                stats = stats(),
                content = content,
                reasoning = reasoning,
            ),
        )
        events.close()
    }

    override fun cancel() {
        cancelCount++
        events.close()
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
        contextUsed = contextUsed,
        contextSize = loaded?.contextSize ?: 0,
    )

    private companion object {
        const val REPLY = "A short answer."
    }
}

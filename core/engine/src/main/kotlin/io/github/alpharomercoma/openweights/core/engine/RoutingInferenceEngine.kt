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
import io.github.alpharomercoma.openweights.core.common.model.ModelFormat
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Hands each model to the engine that can read it, and keeps only one of them loaded.
 *
 * Callers see a single [InferenceEngine] whose model happens to change runtime underneath.
 * That is the whole point of the split: a `.pte` compiled for the NPU and a GGUF read
 * straight off the Hub are the same thing to a conversation, and nothing above this class
 * should have to know which one it is talking to.
 *
 * **One model at a time, across both engines.** Weights are hundreds of megabytes of
 * resident memory, so loading into one backend unloads the other first. Without that, a
 * user switching between a GGUF and a `.pte` would hold two models and be killed for it.
 *
 * Backends are built on first use rather than up front. Constructing the ExecuTorch engine
 * loads a native library that is only present in builds that ship one, and a phone that
 * never opens a `.pte` should never pay for it or fail because of it.
 */
class RoutingInferenceEngine(
    private val backends: Map<ModelFormat, () -> InferenceEngine>,
    /**
     * The engine that answers questions asked before anything is loaded.
     *
     * Settings enumerates compute devices and prints system info on a cold start, when no
     * model has been chosen. Those answers are about the phone rather than the model, and
     * llama.cpp is the backend that can always give them.
     */
    private val defaultFormat: ModelFormat = ModelFormat.GGUF,
) : InferenceEngine {

    private val built = mutableMapOf<ModelFormat, InferenceEngine>()

    /** The backend currently holding weights. Null until something loads. */
    private var activeFormat: ModelFormat? = null

    private val active: InferenceEngine? get() = activeFormat?.let { built[it] }

    override val loadedModel: LoadedModelInfo? get() = active?.loadedModel

    override suspend fun load(modelFile: File, params: ModelLoadParams, projectorFile: File?) {
        val format = ModelFormat.of(modelFile.name)
            ?: throw LlamaException("Not a model this app can run: ${modelFile.name}")
        val engine = engineFor(format)

        // Free the other runtime's weights before allocating ours, not after.
        activeFormat?.takeIf { it != format }?.let { built[it]?.unload() }

        engine.load(modelFile, params, projectorFile)
        activeFormat = format
    }

    override suspend fun unload() {
        active?.unload()
        activeFormat = null
    }

    override fun chat(
        messages: List<ChatMessage>,
        params: SamplerParams,
        tools: List<ToolDefinition>,
    ): Flow<GenerationEvent> = flow {
        // Resolved when collected rather than when built, so a flow made before a load and
        // collected after it still reaches the engine that ended up with the model. The
        // engines below are cold in the same way, so nothing starts until this does.
        val engine = active ?: throw LlamaException("No model loaded")
        emitAll(engine.chat(messages, params, tools))
    }

    override suspend fun warm(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        params: SamplerParams,
        snapshot: Boolean,
        store: String?,
    ): WarmResult? = active?.warm(messages, tools, params, snapshot, store)

    override fun cancel() {
        active?.cancel()
    }

    override suspend fun resetContext() {
        active?.resetContext()
    }

    override suspend fun setThreads(generateThreads: Int, batchThreads: Int) {
        active?.setThreads(generateThreads, batchThreads)
    }

    override fun systemInfo(): String = describing().systemInfo()

    override fun computeDevices(): List<ComputeDevice> = describing().computeDevices()

    override fun close() {
        built.values.forEach { it.close() }
        built.clear()
        activeFormat = null
    }

    /** The engine to ask about the hardware: whichever holds a model, else the default. */
    private fun describing(): InferenceEngine = active ?: engineFor(defaultFormat)

    private fun engineFor(format: ModelFormat): InferenceEngine = built.getOrPut(format) {
        val factory = backends[format]
            ?: throw LlamaException("This build has no engine for ${format.suffix} models")
        factory()
    }
}

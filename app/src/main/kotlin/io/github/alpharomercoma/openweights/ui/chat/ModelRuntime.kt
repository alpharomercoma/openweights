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

import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.data.ModelPreferencesRepository
import io.github.alpharomercoma.openweights.core.device.ThermalLevel
import io.github.alpharomercoma.openweights.core.device.ThermalPolicy
import io.github.alpharomercoma.openweights.core.engine.ComputeDeviceKind
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LoadedModelInfo
import io.github.alpharomercoma.openweights.model.ModelStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The loaded model and the machine it runs on.
 *
 * Four collaborators that only ever appear together: the engine, where model files live,
 * what settings are saved against a model, and how hot the phone is. The chat view model
 * held all four and three separate static checks said it was holding too much. None of
 * this is about a conversation, which is why it is not in there any more.
 */
@Singleton
// Lifecycle wrappers stay here so engine policy does not leak into the UI.
@Suppress("TooManyFunctions")
class ModelRuntime @Inject constructor(
    private val engine: InferenceEngine,
    private val modelStore: ModelStore,
    private val preferences: ModelPreferencesRepository,
    private val thermal: ThermalPolicy,
    /** How wide a window to open a model with, when nobody has chosen one. */
    val windows: ContextWindows,
) {
    val loadedModel: LoadedModelInfo? get() = engine.loadedModel

    /** The model to open with: the one last chosen, or any that is on disk. */
    fun preferredModel(): File? = modelStore.preferredModel()

    /** Records the choice, so the next launch opens the same model. */
    fun rememberChoice(model: File) = modelStore.rememberChoice(model)

    fun projectorFor(model: File): File? = modelStore.projectorFor(model)

    suspend fun settingsFor(fileName: String): ModelPreferences = preferences.current(fileName)

    suspend fun saveSettings(fileName: String, value: ModelPreferences) =
        preferences.save(fileName, value)

    suspend fun resetSettings(fileName: String) = preferences.reset(fileName)

    suspend fun load(model: File, params: ModelLoadParams, projector: File?) =
        engine.load(model, params, projector)

    /** Releases weights and the KV cache without deleting the model from storage. */
    suspend fun unload() = engine.unload()

    /**
     * Clears the KV cache.
     *
     * Called when the weights change or the conversation does, because the cache belongs
     * to both and carrying it across either one decodes against positions nothing wrote.
     */
    suspend fun resetContext() = engine.resetContext()

    /**
     * Where the weights are, not what the phone has.
     *
     * This used to be `computeDevices().first()`, which is the first backend ggml
     * registered and is always the CPU, so the top bar read "CPU" for every model on every
     * device however the processor setting was set. Nobody could tell whether asking for
     * the GPU had done anything, because the label was not derived from the answer.
     *
     * It now comes from llama.cpp's own accounting of which buffers the tensors landed in,
     * captured at load. Falling back to the registered device keeps the line populated for
     * a model loaded before this existed rather than blanking the bar.
     */
    fun backendName(): String? = engine.loadedModel?.offloadedTo?.takeIf { it.isNotBlank() }
        ?.uppercase()
        ?: engine.computeDevices().firstOrNull()?.id?.uppercase()

    /**
     * True when there is something other than the CPU to hand layers to.
     *
     * Asked so the offload control appears only where moving it does anything. A phone with
     * no working GPU backend gets a setting that cannot change the answer, which is worse
     * than no setting.
     */
    fun hasGpu(): Boolean = runCatching {
        engine.computeDevices().any { it.kind == ComputeDeviceKind.GPU }
    }.getOrDefault(false)

    fun isThrottling(): Boolean = thermal.isThrottling()

    /** How hot the system says the device is, for the line that says so while working. */
    fun thermalLevel(): ThermalLevel = thermal.level()

    /** The device temperature in degrees, or null where the platform will not say. */
    fun thermalCelsius(): Float? = thermal.celsius()

    /** True once this model has been caught reasoning after being told not to. */
    fun ignoresThinkingSwitch(model: String): Boolean = modelStore.ignoresThinkingSwitch(model)

    /** Remembers that it does, so the switch stops being offered for it. */
    fun rememberIgnoresThinkingSwitch(model: String) =
        modelStore.rememberIgnoresThinkingSwitch(model)

    /**
     * Re-plans the thread count for the next reply.
     *
     * Per reply because the phone is a different machine hot than cold, and a count chosen
     * at load time is wrong by the third long answer.
     *
     * @return false when the device is hot enough that the right move is to stop rather
     * than to stop slightly less. Sustained inference is among the heaviest things this
     * hardware can be asked to do.
     */
    suspend fun planThreads(): Boolean {
        val plan = thermal.plan()
        if (plan.shouldPause) return false
        engine.setThreads(plan.generateThreads, plan.batchThreads)
        return true
    }

    fun cancel() = engine.cancel()
}

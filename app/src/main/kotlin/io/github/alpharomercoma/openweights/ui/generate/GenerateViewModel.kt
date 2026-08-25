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

package io.github.alpharomercoma.openweights.ui.generate

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.core.generation.Artifact
import io.github.alpharomercoma.openweights.core.generation.GenerationBundle
import io.github.alpharomercoma.openweights.core.generation.GenerationBundleSpec
import io.github.alpharomercoma.openweights.core.generation.GenerationCatalog
import io.github.alpharomercoma.openweights.core.generation.GenerationEvent
import io.github.alpharomercoma.openweights.core.generation.GenerationRuntime
import io.github.alpharomercoma.openweights.core.generation.GenerationTask
import io.github.alpharomercoma.openweights.core.generation.ImageCapability
import io.github.alpharomercoma.openweights.core.generation.ImageRequest
import io.github.alpharomercoma.openweights.core.generation.ImageSize
import io.github.alpharomercoma.openweights.core.generation.mnn.MnnImageGenerator
import io.github.alpharomercoma.openweights.model.ModelStore
import io.github.alpharomercoma.openweights.runtime.GenerationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** A completed image generation run shown in the result pane. */
data class GenerationResult(
    val imagePath: String,
    val prompt: String,
    val totalMillis: Long,
    val seed: Long,
    val backend: String,
)

/**
 * What the Generate screen shows at any moment.
 */
data class GenerateUiState(
    val availableBundles: List<GenerationBundleSpec> = emptyList(),
    val selectedBundleSpec: GenerationBundleSpec? = null,
    val capability: ImageCapability? = null,
    val prompt: String = "",
    val steps: Int = 10,
    val guidance: Float = 4.5f,
    val size: ImageSize = ImageSize(512, 512),
    val isGenerating: Boolean = false,
    val progressStep: Int = 0,
    val lastResult: GenerationResult? = null,
    val error: String? = null,
    val runtimeMissing: Boolean = false,
) {
    val canGenerate: Boolean
        get() = !isGenerating && capability != null && prompt.isNotBlank()
}

private const val TAG = "GenerateVM"

@HiltViewModel
class GenerateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelStore: ModelStore,
) : ViewModel() {

    private val _state = MutableStateFlow(GenerateUiState())
    val state: StateFlow<GenerateUiState> = _state.asStateFlow()

    /**
     * One generator kept alive as long as this screen is reachable.
     * Closed in [onCleared].
     */
    private var generator: MnnImageGenerator? = null
    private var generateJob: Job? = null

    private val outputDir: File
        get() = File(context.filesDir, "generated").also { it.mkdirs() }

    init {
        if (!MnnImageGenerator.isAvailable) {
            _state.update { it.copy(runtimeMissing = true) }
            Log.i(TAG, "MNN runtime absent in this build")
        }
        refreshBundles()
    }

    /**
     * Rescans the model directory.
     * Call when returning from Models or Discover so newly-downloaded bundles appear.
     */
    fun refreshBundles() {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = modelStore.availableBundles()
                .mapNotNull { dir -> GenerationCatalog.findByDirectory(dir.name) }
                .filter { it.task == GenerationTask.IMAGE }

            val current = _state.value
            val next = when {
                current.selectedBundleSpec == null && installed.isNotEmpty() -> installed.first()
                current.selectedBundleSpec != null && installed.none {
                    it.id == current.selectedBundleSpec.id
                } -> installed.firstOrNull()
                else -> current.selectedBundleSpec
            }
            _state.update { it.copy(availableBundles = installed, selectedBundleSpec = next) }

            if (next != null && _state.value.capability == null) {
                loadBundle(next)
            }
        }
    }

    fun selectBundle(spec: GenerationBundleSpec) {
        if (_state.value.selectedBundleSpec?.id == spec.id) return
        _state.update { it.copy(selectedBundleSpec = spec, capability = null, error = null) }
        viewModelScope.launch { loadBundle(spec) }
    }

    private suspend fun loadBundle(spec: GenerationBundleSpec) {
        if (_state.value.runtimeMissing) return
        val dir = modelStore.bundleDestination(spec.directoryName)
        val bundle = GenerationBundle(
            id = spec.id,
            displayName = spec.displayName,
            task = GenerationTask.IMAGE,
            runtime = GenerationRuntime.MNN,
            files = listOf(Artifact(File(dir, "config.json").absolutePath, "application/json")),
            quantization = spec.quantization,
            minimumFreeBytes = spec.minimumFreeBytes,
            licence = spec.licence,
            mnnModelType = spec.mnnModelType,
        )
        _state.update { it.copy(error = null) }
        withContext(Dispatchers.IO) {
            try {
                val gen = generator ?: MnnImageGenerator(outputDir).also { generator = it }
                gen.load(bundle)
                val capability = gen.capability
                // Guidance means different things at different scales on different runtimes
                // (Sana measured needing ~15 to converge; SD 1.5 is fixed at 7.5) -- carrying a
                // leftover value from whichever model was loaded before would silently apply
                // the wrong model's scale to this one.
                _state.update {
                    it.copy(
                        capability = capability,
                        guidance = capability?.defaultGuidance ?: it.guidance,
                    )
                }
                Log.i(TAG, "loaded ${spec.displayName}: capability=$capability")
            } catch (e: Exception) {
                Log.e(TAG, "load failed: ${spec.displayName}", e)
                _state.update { it.copy(error = "Could not load ${spec.displayName}: ${e.message}") }
            }
        }
    }

    fun setPrompt(text: String) = _state.update { it.copy(prompt = text, error = null) }
    fun setSteps(steps: Int) = _state.update { it.copy(steps = steps.coerceAtLeast(1)) }
    fun setGuidance(guidance: Float) = _state.update { it.copy(guidance = guidance) }
    fun setSize(size: ImageSize) = _state.update { it.copy(size = size) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun generate() {
        val s = _state.value
        if (!s.canGenerate) return
        generateJob?.cancel()
        generateJob = viewModelScope.launch(Dispatchers.IO) {
            val gen = generator ?: return@launch
            val request = ImageRequest(
                prompt = s.prompt,
                size = s.size,
                steps = s.steps,
                guidance = s.guidance,
                seed = System.currentTimeMillis(),
            )
            _state.update { it.copy(isGenerating = true, progressStep = 0, error = null) }
            GenerationService.hold(context, HOLDER, "Generating image\u2026")
            try {
                gen.generate(request)
                    .onEach { event ->
                        when (event) {
                            is GenerationEvent.Progress ->
                                _state.update { it.copy(progressStep = event.step) }
                            is GenerationEvent.Completed<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                val done = event as GenerationEvent.Completed<Artifact>
                                _state.update { st ->
                                    st.copy(
                                        isGenerating = false,
                                        lastResult = GenerationResult(
                                            imagePath = done.output.path,
                                            prompt = s.prompt,
                                            totalMillis = done.stats.totalMillis,
                                            seed = done.stats.seed,
                                            backend = done.stats.backend,
                                        ),
                                    )
                                }
                            }
                            is GenerationEvent.Failed ->
                                _state.update { it.copy(isGenerating = false, error = event.reason) }
                            GenerationEvent.Cancelled ->
                                _state.update { it.copy(isGenerating = false) }
                            GenerationEvent.Started -> Unit
                        }
                    }
                    .catch { e ->
                        if (e is CancellationException) throw e
                        Log.e(TAG, "generation threw", e)
                        _state.update { it.copy(isGenerating = false, error = e.message ?: "Generation failed") }
                    }
                    .collect()
            } finally {
                _state.update { it.copy(isGenerating = false) }
                GenerationService.release(context, HOLDER)
            }
        }
    }

    fun cancel() {
        generator?.cancel()
        generateJob?.cancel()
        _state.update { it.copy(isGenerating = false) }
    }

    override fun onCleared() {
        super.onCleared()
        generator?.close()
        generator = null
    }

    private companion object {
        const val HOLDER = "image_generate"
    }
}

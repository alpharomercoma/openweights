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
import android.net.Uri
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
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.generation.mnn.MnnImageGenerator
import io.github.alpharomercoma.openweights.model.AttachmentResult
import io.github.alpharomercoma.openweights.model.AttachmentStore
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * True while [capability] is being fetched for [selectedBundleSpec].
     *
     * A first run builds the OpenCL kernel cache for this model, which alone can take on
     * the order of thirty seconds -- and until it's done, [capability] is null, the Steps
     * slider has nothing to size itself against and stays hidden, and Generate stays
     * disabled. Without this flag none of that had a visible cause: the screen looked
     * finished loading a moment after opening it, not partway through something slow.
     */
    val isLoadingCapability: Boolean = false,
    val prompt: String = "",
    /** A picture to edit rather than generate from scratch. Only settable when [capability]'s
     * `supportsImageEdit` is true. */
    val referenceImage: MessagePart.File? = null,
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
    private val attachmentStore: AttachmentStore,
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

    /**
     * Serializes [loadBundle] against itself.
     *
     * refreshBundles() used to be called from both this view model's own init block and the
     * screen's LaunchedEffect(Unit), which fires on every composition including the first --
     * so two calls, a few milliseconds apart, each read capability as still null and each
     * started their own ~30s model load. Both usually finished (this is a real MNN generator
     * per call, not a no-op), leaving whichever loaded second holding the handle Generate
     * actually uses while the *other* one's state update could still land after it and show
     * a session that had already been superseded -- capability looked populated, but the
     * generator backing the screen's next actual generate() call was whichever lost that
     * race, sporadically the wrong -- or, worse, already-torn-down -- one. The init-block
     * call is gone now (the LaunchedEffect covers first-open the same as every later
     * return), but this stays as the actual fix: nothing calls loadBundle while another call
     * is still in flight, no matter how many places end up asking for a refresh.
     */
    private val loadMutex = Mutex()

    init {
        if (!MnnImageGenerator.isAvailable) {
            _state.update { it.copy(runtimeMissing = true) }
            Log.i(TAG, "MNN runtime absent in this build")
        }
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
        // A reference image staged for one model's img2img path is meaningless to another --
        // keeping it around silently would let a switch to SD 1.5 leave a picture attached
        // that its request never reads, with nothing on screen explaining why.
        clearReferenceImage()
        _state.update { it.copy(selectedBundleSpec = spec, capability = null, error = null) }
        viewModelScope.launch { loadBundle(spec) }
    }

    private suspend fun loadBundle(spec: GenerationBundleSpec) = loadMutex.withLock {
        if (_state.value.runtimeMissing) return@withLock
        // Whatever queued behind another call's lock has, by now, likely been superseded --
        // re-read rather than trust the spec the caller queued with.
        if (_state.value.selectedBundleSpec?.id != spec.id) return@withLock
        if (_state.value.capability != null) return@withLock
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
        _state.update { it.copy(error = null, isLoadingCapability = true) }
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
                        isLoadingCapability = false,
                    )
                }
                Log.i(TAG, "loaded ${spec.displayName}: capability=$capability")
            } catch (e: Exception) {
                Log.e(TAG, "load failed: ${spec.displayName}", e)
                _state.update {
                    it.copy(
                        error = "Could not load ${spec.displayName}: ${e.message}",
                        isLoadingCapability = false,
                    )
                }
            }
        }
    }

    fun setPrompt(text: String) = _state.update { it.copy(prompt = text, error = null) }

    /** Stages [uri] as the picture to edit. Replaces and discards whatever was staged before. */
    fun attachReferenceImage(uri: Uri) {
        val previous = _state.value.referenceImage
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = attachmentStore.store(uri)) {
                is AttachmentResult.Stored -> {
                    val picked = result.files.first()
                    _state.update { it.copy(referenceImage = picked, error = null) }
                    previous?.let { attachmentStore.discard(it) }
                }
                is AttachmentResult.TooLarge ->
                    _state.update { it.copy(error = "That image is too large to attach.") }
                AttachmentResult.Unreadable ->
                    _state.update { it.copy(error = "Could not read that image.") }
            }
        }
    }

    fun clearReferenceImage() {
        val current = _state.value.referenceImage ?: return
        _state.update { it.copy(referenceImage = null) }
        viewModelScope.launch(Dispatchers.IO) { attachmentStore.discard(current) }
    }

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
                referenceImage = s.referenceImage
                    ?.takeIf { s.capability?.supportsImageEdit == true }
                    ?.let { Artifact(it.path, it.mediaType) },
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

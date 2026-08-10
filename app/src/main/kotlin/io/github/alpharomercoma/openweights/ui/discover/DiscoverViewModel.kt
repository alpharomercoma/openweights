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

package io.github.alpharomercoma.openweights.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.common.model.GgufMetadata
import io.github.alpharomercoma.openweights.core.device.DeviceProfiler
import io.github.alpharomercoma.openweights.core.device.FitEstimator
import io.github.alpharomercoma.openweights.core.device.FitReport
import io.github.alpharomercoma.openweights.core.hub.HubFile
import io.github.alpharomercoma.openweights.core.hub.HubModel
import io.github.alpharomercoma.openweights.core.hub.HubModelDetail
import io.github.alpharomercoma.openweights.core.hub.HubQuery
import io.github.alpharomercoma.openweights.core.hub.HubSort
import io.github.alpharomercoma.openweights.core.hub.HubTask
import io.github.alpharomercoma.openweights.core.hub.HuggingFaceClient
import io.github.alpharomercoma.openweights.core.hub.ParameterRange
import io.github.alpharomercoma.openweights.core.hub.gguf.GgufHeaderParser
import io.github.alpharomercoma.openweights.model.ModelStore
import io.github.alpharomercoma.openweights.model.RangeByteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/** One downloadable file with everything known about how it would run here. */
data class InspectedFile(
    val file: HubFile,
    val metadata: GgufMetadata? = null,
    val fit: FitReport? = null,
    val isInspecting: Boolean = false,
    val inspectionError: String? = null,
    val isDownloaded: Boolean = false,
)

data class DiscoverUiState(
    val query: HubQuery = HubQuery(),
    val results: List<HubModel> = emptyList(),
    val isSearching: Boolean = false,
    val detail: HubModelDetail? = null,
    val files: List<InspectedFile> = emptyList(),
    val contextLength: Int = DEFAULT_CONTEXT,
    val error: String? = null,
    /**
     * The parameter count this phone can hold, in billions.
     *
     * Shown on the size filter so the bands mean something: "8B to 16B" is advice only if
     * you know which side of it your phone is on.
     */
    val parameterCeilingBillions: Int = 0,
) {
    companion object {
        const val DEFAULT_CONTEXT = 4096
    }
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val client: HuggingFaceClient,
    private val profiler: DeviceProfiler,
    private val estimator: FitEstimator,
    private val rangeSourceFactory: RangeByteSource.Factory,
    private val modelStore: ModelStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    /** Runs the query a moment after typing stops, so every keystroke is not a request. */
    private var typingJob: Job? = null

    /**
     * Everything belonging to the repository currently open.
     *
     * Opening B while A is still loading must not let A's late reply overwrite B, and A's
     * per-file inspections must stop rather than write into B's list: filenames repeat
     * across repositories, so they would land on the wrong rows.
     */
    private var detailJob: Job? = null

    /** Header inspections are network-bound; a repo with twenty files should not fire twenty at once. */
    private val inspectionLimit = Semaphore(MAX_CONCURRENT_INSPECTIONS)

    init {
        val ceiling = estimator.parameterCeilingBillions(profiler.profile())
        _uiState.update { it.copy(parameterCeilingBillions = ceiling) }
        search()
    }

    fun onQueryChange(text: String) {
        _uiState.update { it.copy(query = it.query.copy(text = text)) }
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(TYPING_PAUSE_MS)
            search()
        }
    }

    /** Applies a whole set of filters at once, as the filter sheet does. */
    fun onQueryChange(query: HubQuery) {
        typingJob?.cancel()
        _uiState.update { it.copy(query = query) }
        search()
    }

    fun onSortChange(sort: HubSort) = onQueryChange(_uiState.value.query.copy(sort = sort))

    fun onTaskChange(task: HubTask?) = onQueryChange(_uiState.value.query.copy(task = task))

    /** Picking a band turns off the device ceiling: they answer the same question. */
    fun onParameterRangeChange(range: ParameterRange) = onQueryChange(
        _uiState.value.query.copy(parameters = range, maxParametersBillions = null),
    )

    /**
     * Narrows the search to models this phone can hold, or widens it again.
     *
     * The one filter no other model browser can offer, because it is the only one that has
     * to know what is in your hand.
     */
    fun onPhoneSizedChange(enabled: Boolean) = onQueryChange(
        _uiState.value.query.copy(
            parameters = ParameterRange.ANY,
            maxParametersBillions = _uiState.value.parameterCeilingBillions.takeIf { enabled },
        ),
    )

    fun clearFilters() = onQueryChange(HubQuery(text = _uiState.value.query.text))

    fun search() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null) }
            runCatching { client.search(_uiState.value.query) }
                .onSuccess { results ->
                    _uiState.update { it.copy(isSearching = false, results = results) }
                }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(isSearching = false, error = failure.readableMessage())
                    }
                }
        }
    }

    /** Opens a repository and starts inspecting its files against this device. */
    fun openModel(repoId: String) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _uiState.update { it.copy(detail = null, files = emptyList(), error = null) }
            runCatching { client.detail(repoId) }
                .onSuccess { detail ->
                    val downloaded = modelStore.availableModels().map { it.name }.toSet()
                    _uiState.update { state ->
                        state.copy(
                            detail = detail,
                            files = detail.files.map { file ->
                                InspectedFile(
                                    file = file,
                                    isDownloaded = file.path.substringAfterLast('/') in downloaded,
                                )
                            },
                        )
                    }
                    coroutineScope {
                        detail.files.forEach { file -> launch { inspect(repoId, file) } }
                    }
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(error = failure.readableMessage()) }
                }
        }
    }

    fun closeModel() {
        detailJob?.cancel()
        _uiState.update { it.copy(detail = null, files = emptyList()) }
    }

    /** Re-runs the fit maths at a new context length without touching the network. */
    fun onContextLengthChange(contextLength: Int) {
        _uiState.update { state ->
            state.copy(
                contextLength = contextLength,
                files = state.files.map { inspected ->
                    inspected.metadata?.let { metadata ->
                        inspected.copy(fit = estimate(metadata, inspected.file, contextLength))
                    } ?: inspected
                },
            )
        }
    }

    /**
     * Reads one file's GGUF header over range requests and works out how it would run.
     *
     * This is the answer to "will it run?", and it costs a couple of kilobytes rather than
     * the gigabytes downloading the file to find out would.
     */
    private suspend fun inspect(repoId: String, file: HubFile) = inspectionLimit.withPermit {
        updateFile(file.path) { it.copy(isInspecting = true, inspectionError = null) }

        runCatching {
            val source = rangeSourceFactory.create(client.downloadUrl(repoId, file.path))
            GgufHeaderParser(source).parse()
        }.onSuccess { metadata ->
            val fit = estimate(metadata, file, _uiState.value.contextLength)
            updateFile(file.path) {
                it.copy(isInspecting = false, metadata = metadata, fit = fit)
            }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            updateFile(file.path) {
                it.copy(isInspecting = false, inspectionError = failure.readableMessage())
            }
        }
    }

    private fun estimate(metadata: GgufMetadata, file: HubFile, contextLength: Int): FitReport =
        estimator.estimate(
            device = profiler.profile(),
            metadata = metadata,
            fileSizeBytes = file.sizeBytes,
            contextLength = contextLength,
            projectorSizeBytes = _uiState.value.detail?.pairedProjector(file)?.sizeBytes ?: 0,
        )

    private fun updateFile(path: String, transform: (InspectedFile) -> InspectedFile) {
        _uiState.update { state ->
            state.copy(
                files = state.files.map { if (it.file.path == path) transform(it) else it },
            )
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private companion object {
        const val MAX_CONCURRENT_INSPECTIONS = 3

        /**
         * How long typing has to stop before the query runs.
         *
         * Long enough that a word typed at speed is one request rather than six, short
         * enough that the list feels like it is keeping up.
         */
        const val TYPING_PAUSE_MS = 350L
    }
}

internal fun Throwable.readableMessage(): String =
    message ?: "Something went wrong (${this::class.simpleName})."

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
import io.github.alpharomercoma.openweights.core.hub.HubSort
import io.github.alpharomercoma.openweights.core.hub.HuggingFaceClient
import io.github.alpharomercoma.openweights.core.hub.gguf.GgufHeaderParser
import io.github.alpharomercoma.openweights.model.ModelStore
import io.github.alpharomercoma.openweights.model.RangeByteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
    val query: String = "",
    val sort: HubSort = HubSort.DOWNLOADS,
    val results: List<HubModel> = emptyList(),
    val isSearching: Boolean = false,
    val detail: HubModelDetail? = null,
    val files: List<InspectedFile> = emptyList(),
    val contextLength: Int = DEFAULT_CONTEXT,
    val error: String? = null,
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

    /**
     * Everything belonging to the repository currently open.
     *
     * Opening B while A is still loading must not let A's late reply overwrite B, and A's
     * per-file inspections must stop rather than write into B's list — filenames repeat
     * across repositories, so they would land on the wrong rows.
     */
    private var detailJob: Job? = null

    /** Header inspections are network-bound; a repo with twenty files should not fire twenty at once. */
    private val inspectionLimit = Semaphore(MAX_CONCURRENT_INSPECTIONS)

    init {
        search("")
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun onSortChange(sort: HubSort) {
        _uiState.update { it.copy(sort = sort) }
        search(_uiState.value.query)
    }

    fun search(query: String = _uiState.value.query) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null) }
            runCatching { client.search(query, _uiState.value.sort) }
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
            projectorSizeBytes = _uiState.value.detail?.pairedProjector()?.sizeBytes ?: 0,
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
    }
}

internal fun Throwable.readableMessage(): String =
    message ?: "Something went wrong (${this::class.simpleName})."

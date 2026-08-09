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

package io.github.alpharomercoma.openweights.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.hub.DownloadProgress
import io.github.alpharomercoma.openweights.core.hub.HubFile
import io.github.alpharomercoma.openweights.core.hub.ModelDownloader
import io.github.alpharomercoma.openweights.model.ModelStore
import io.github.alpharomercoma.openweights.ui.discover.readableMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** A model file on disk. */
data class LocalModel(val file: File) {
    val name: String get() = file.nameWithoutExtension
    val sizeBytes: Long get() = file.length()
}

/** A download in flight, keyed by the file it is fetching. */
data class ActiveDownload(
    val repoId: String,
    val path: String,
    /** The filename being written; downloads are identified by destination, not source. */
    val key: String,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val isVerifying: Boolean = false,
    val error: String? = null,
) {
    val fraction: Float get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
}

data class ModelsUiState(
    val models: List<LocalModel> = emptyList(),
    val downloads: List<ActiveDownload> = emptyList(),
    val storageUsedBytes: Long = 0,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val downloader: ModelDownloader,
    private val modelStore: ModelStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    init {
        refresh()
    }

    fun refresh() {
        val models = modelStore.availableModels().map(::LocalModel)
        _uiState.update {
            it.copy(models = models, storageUsedBytes = models.sumOf { model -> model.sizeBytes })
        }
    }

    /**
     * Starts a download, or does nothing if that file is already being fetched.
     *
     * Downloads survive leaving the screen because they run in the view model scope; the
     * partial file on disk means even process death only costs the current chunk.
     */
    /**
     * Starts a download, or does nothing if that destination is already being fetched.
     *
     * Keyed by the file it writes rather than the remote path: two repositories can offer
     * the same filename, and letting both run would have them overwrite each other's
     * bytes in the same partial file.
     */
    fun download(repoId: String, path: String, sizeBytes: Long, sha256: String?) {
        val destination = File(modelStore.directory, path.substringAfterLast('/'))
        val key = destination.name
        if (jobs.containsKey(key)) return

        _uiState.update { it.copy(downloads = it.downloads + ActiveDownload(repoId, path, key)) }

        val job = viewModelScope.launch {
            downloader.download(repoId, HubFile(path, sizeBytes, sha256), destination)
                .catch { failure ->
                    updateDownload(key) { it.copy(error = failure.readableMessage()) }
                }
                .collect { progress -> apply(key, progress) }
        }
        jobs[key] = job
        // Cancellation skips anything after collect, so clearing the entry has to be tied
        // to completion or a cancelled download could never be retried.
        job.invokeOnCompletion { jobs.remove(key) }
    }

    fun cancel(key: String) {
        jobs[key]?.cancel()
        _uiState.update { it.copy(downloads = it.downloads.filterNot { d -> d.key == key }) }
    }

    fun delete(model: LocalModel) {
        model.file.delete()
        refresh()
    }

    private fun apply(path: String, progress: DownloadProgress) {
        when (progress) {
            is DownloadProgress.Downloading -> updateDownload(path) {
                it.copy(bytesDone = progress.bytesDone, bytesTotal = progress.bytesTotal)
            }

            DownloadProgress.Verifying -> updateDownload(path) { it.copy(isVerifying = true) }

            is DownloadProgress.Finished -> {
                _uiState.update {
                    it.copy(downloads = it.downloads.filterNot { d -> d.path == path })
                }
                refresh()
            }
        }
    }

    private fun updateDownload(path: String, transform: (ActiveDownload) -> ActiveDownload) {
        _uiState.update { state ->
            state.copy(
                downloads = state.downloads.map { if (it.path == path) transform(it) else it },
            )
        }
    }
}

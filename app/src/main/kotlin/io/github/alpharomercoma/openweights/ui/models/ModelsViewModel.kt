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

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.hub.HubFile
import io.github.alpharomercoma.openweights.core.hub.Publishers
import io.github.alpharomercoma.openweights.download.ModelDownloadWorker
import io.github.alpharomercoma.openweights.model.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** A model file on disk. */
data class LocalModel(
    val file: File,
    /** The projector paired with this model, when one has been downloaded. */
    val projector: File? = null,
    /**
     * Who published it, when the app was the one that fetched it.
     *
     * Null for a file put here by hand, or downloaded by a build that did not record it.
     * Those group under a heading of their own rather than being guessed at from the
     * filename: "LFM2.5-2.6B" does not say LiquidAI to anybody who does not already know,
     * and a wrong attribution is worse than none.
     */
    val publisher: String? = null,
) {
    val name: String get() = file.nameWithoutExtension

    /** What this model occupies in total, projector included. */
    val sizeBytes: Long get() = file.length() + (projector?.length() ?: 0)

    /** True when this model can read attachments on this device. */
    val isMultimodal: Boolean get() = projector != null

    /**
     * True for weights compiled ahead of time, which only the ExecuTorch engine can run.
     *
     * The extension is the whole truth here: the app names every compiled download
     * `<name>.pte`, and which engine loads a file is decided the same way. Shown beside
     * the size wherever a model can be chosen, because two files of the same family and
     * size behave differently — different speed, different context window — and the
     * runtime is the only visible thing that says which one this is.
     */
    val isCompiled: Boolean get() = file.extension.equals("pte", ignoreCase = true)

    /**
     * True when the filename strongly suggests a vision-language model but no projector
     * has been downloaded.
     *
     * Heuristic only — based on common VLM naming conventions. Displayed as a warning chip
     * in the model list and picker so users know why the attachment button stays hidden.
     */
    val looksLikeVlm: Boolean
        get() = projector == null &&
            VLM_PATTERNS.any { pattern -> file.name.contains(pattern, ignoreCase = true) }

    private companion object {
        /** Substrings common to vision-language model filenames across every major family. */
        val VLM_PATTERNS = listOf(
            "-VL", "-vl", "Vision", "vision", "Llava", "llava",
            "Pixtral", "pixtral", "InternVL", "interVL", "QwenVL", "qwenvl", "Gemma3n",
            "gemma3n", "phi-4-mm", "Phi-4-mm",
        )
    }
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
    /**
     * Whether the directory has actually been read. "No models" is a real answer and it
     * is byte-identical to the state this screen is seeded with, so without this flag a
     * pipeline that never spoke was indistinguishable from an empty phone — on screen
     * and, worse, in the test meant to catch exactly that.
     */
    val listed: Boolean = false,
    val downloads: List<ActiveDownload> = emptyList(),
    val storageUsedBytes: Long = 0,
    /**
     * Publisher name to logo, filled in as the lookups come back.
     *
     * Best effort and often empty. The lookup needs the network and this app is used
     * offline, so a heading with no logo beside it is the normal case rather than a
     * failure, and nothing here waits for one.
     */
    val avatars: Map<String, String> = emptyMap(),
    /**
     * One line about something the screen refused to do, or null.
     *
     * Deleting the model that is currently loaded is the case this exists for. Refusing was
     * right and refusing in silence was not: the row stayed exactly where it was, with no
     * dialog, no error and nothing to read, which reads as a broken button rather than as a
     * rule. Cleared by the next refresh, so it does not outlive the tap that caused it.
     */
    val notice: String? = null,
) {
    /** The installed models under the name of whoever published them. */
    val grouped: List<PublisherGroup> get() = models.byPublisher()
}

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val workManager: WorkManager,
    private val modelStore: ModelStore,
    private val publishers: Publishers,
    private val engine: InferenceEngine,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val local = MutableStateFlow(ModelsUiState())

    /**
     * Downloads the user has waved away, which WorkManager has no concept of.
     *
     * A failed download stays in its database until something replaces it, so without this
     * a failure would sit on the screen with no way to clear it but a retry.
     */
    private val dismissed = MutableStateFlow(emptySet<String>())

    /** Finished work already folded into the model list, so it is folded in once. */
    private val absorbed = mutableSetOf<UUID>()

    /**
     * The download queue, made to say "nothing yet" before it says anything else.
     *
     * On a phone that has never downloaded a model this query emits nothing at all, rather
     * than an empty list, and a combine stays silent until every source has spoken once. The
     * models already on disk therefore never reached the screen: a fresh install showed "no
     * models yet" with gigabytes sitting in its own directory, and the list only appeared
     * after the first download created a row to report. Seeding an empty list costs nothing
     * when there is work, because the real value replaces it immediately.
     */
    private val downloadWork = workManager
        .getWorkInfosByTagFlow(ModelDownloadWorker.TAG_DOWNLOAD)
        .onStart { emit(emptyList()) }

    val uiState: StateFlow<ModelsUiState> = combine(
        local,
        downloadWork,
        dismissed,
    ) { state, work, hidden ->
        state.copy(downloads = work.toDownloads(hidden))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MILLIS),
        initialValue = ModelsUiState(),
    )

    init {
        // Off the main thread: a phone that still has a pre-revert Sana/SD bundle deletes up
        // to 1.6 GB recursively here, and Hilt constructs this view model synchronously
        // during composition, so doing it inline would be a near-guaranteed ANR the first
        // time this build opens the Models screen on such a phone.
        viewModelScope.launch(Dispatchers.IO) {
            modelStore.deleteLegacyGenerationBundles()
            refresh()
        }
        // Last run's finished rows, which are of no interest now: their files are either on
        // disk, where refresh finds them, or they are not, and a failure the user cannot
        // act on any more is just clutter.
        workManager.pruneWork()

        // A second collector rather than a side effect inside the combine above, because
        // this has to keep running when nothing is looking at the screen. A download that
        // completes while the user is in a chat still has to appear in the model list when
        // they come back.
        viewModelScope.launch {
            downloadWork.collect { work ->
                val finished = work.any {
                    it.state == WorkInfo.State.SUCCEEDED && absorbed.add(it.id)
                }
                if (finished) refresh()
            }
        }
    }

    fun refresh() {
        val models = modelStore.availableModels().map { file ->
            LocalModel(file, modelStore.projectorFor(file), modelStore.publisherOf(file.name))
        }
        local.update {
            it.copy(
                models = models,
                listed = true,
                storageUsedBytes = models.sumOf { model -> model.sizeBytes },
                notice = null,
            )
        }
        resolveAvatars(models)
    }

    /**
     * Fetches a logo per publisher, once per process, without holding anything up.
     *
     * The same directory Discover uses, so a session that has browsed already has these and
     * pays nothing. A failure is silent on purpose: an offline phone showing headings with
     * no logos is the app working, and a message about it would be noise on the one screen
     * a person opens when they are trying to free up space.
     */
    private fun resolveAvatars(models: List<LocalModel>) {
        models.mapNotNull { it.publisher }
            .filter { it !in local.value.avatars }
            .distinct()
            .forEach { owner ->
                viewModelScope.launch {
                    val url = runCatching { publishers.lookUp(owner) }.getOrNull()?.avatarUrl
                        ?: return@launch
                    local.update { it.copy(avatars = it.avatars + (owner to url)) }
                }
            }
    }

    /**
     * Starts a download, or does nothing if that destination is already being fetched.
     *
     * Keyed by the file it writes rather than the remote path: two repositories can offer
     * the same filename, and letting both run would have them overwrite each other's bytes
     * in the same partial file. That key is also the unique work name, so the "already
     * running" check is WorkManager's rather than ours, and it holds across a restart.
     */
    fun download(repoId: String, path: String, sizeBytes: Long, sha256: String?) {
        val file = HubFile(path, sizeBytes, sha256)
        // Blank means the repository offered a name that is not one, and `HubFile.fileName`
        // refused it rather than let it decide a path: `File(directory, "")` is the
        // directory, and `File(directory, "..")` is its parent. Nothing is started.
        //
        // No error is surfaced, and that is a considered choice rather than an oversight.
        // Errors here belong to a download row, and there is no row until a download
        // starts; inventing a second error channel for a case that needs a malformed
        // repository listing to reach would be more machinery than the case is worth. It is
        // logged, which is what the next person debugging a missing download will look at.
        val name = file.fileName
        if (name.isBlank()) {
            Log.w("OpenWeights", "refused to download $repoId/$path: unusable file name")
            return
        }
        start(repoId, file, File(modelStore.directory, name))
    }

    /**
     * Fetches a model's projector, saved under a name derived from the model file.
     *
     * Renamed on the way in because upstream names only loosely relate to the model
     * different quantization, different capitalisation, sometimes a bare `mmproj-F16`
     * and a guess at load time means a wrong encoder loaded without complaint.
     */
    fun downloadProjector(repoId: String, projector: HubFile, modelFileName: String) {
        start(repoId, projector, modelStore.projectorDestination(modelFileName))
    }

    /**
     * Installs an ExecuTorch model: its compiled weights, and the tokenizer they need.
     *
     * Two downloads for one install, and both are renamed on the way in. The weights take
     * the repository's name because every `.pte` repository calls the file `model.pte`, and
     * the name is the only thing that says which family it is — a `.pte` carries no
     * metadata to read, so `PromptTemplates` has nothing else to go on.
     *
     * The tokenizer goes first. Neither file is usable alone and the two land
     * independently, so the small one is started first and `ModelStore` shows the model
     * only once both are present. The row therefore appears when it starts working rather
     * than when it starts downloading, and a tokenizer that fails looks like a model that
     * never arrived rather than one that is quietly broken.
     */
    fun downloadCompiled(repoId: String, weights: HubFile, tokenizer: HubFile) {
        val destination = modelStore.compiledDestination(repoId, weights.path)
        start(repoId, tokenizer, modelStore.tokenizerFor(destination.name))
        start(repoId, weights, destination)
    }

    private fun start(repoId: String, file: HubFile, destination: File) {
        val key = destination.name
        // The one moment the app knows where a file came from. Once it has landed it is a
        // .gguf in a folder and the name says nothing about who published it.
        modelStore.rememberPublisher(key, repoId.substringBefore('/', missingDelimiterValue = ""))

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_REPO_ID to repoId,
                    ModelDownloadWorker.KEY_PATH to file.path,
                    ModelDownloadWorker.KEY_DESTINATION to destination.absolutePath,
                    ModelDownloadWorker.KEY_SIZE_BYTES to file.sizeBytes,
                    ModelDownloadWorker.KEY_SHA256 to file.sha256,
                ),
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(ModelDownloadWorker.TAG_DOWNLOAD)
            .addTag(ModelDownloadWorker.TAG_KEY + key)
            .addTag(ModelDownloadWorker.TAG_REPO + repoId)
            .addTag(ModelDownloadWorker.TAG_PATH + file.path)
            .addTag(ModelDownloadWorker.TAG_SIZE + file.sizeBytes)
            .build()

        // KEEP, so tapping download twice does not start a second writer on the same file.
        // A run that has already finished is not kept, which is what makes a failed download
        // retryable from the same button.
        workManager.enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, request)
        dismissed.update { it - key }
    }

    fun cancel(key: String) {
        workManager.cancelUniqueWork(key)
        // Also covers dismissing a failure, which has nothing left to cancel.
        dismissed.update { it + key }
    }

    fun delete(model: LocalModel) {
        // Off the main thread, as the sweep in init already is and for the reason given
        // there: unlinking gigabytes and re-listing the directory from a button's onClick
        // held the frame for as long as the filesystem took.
        viewModelScope.launch(Dispatchers.IO) { deleteNow(model) }
    }

    private fun deleteNow(model: LocalModel) {
        // A mapped model can survive an unlink on some filesystems but not all engines
        // tolerate the backing file disappearing while a generation is in flight, so the
        // loaded model is not deleted out from under the engine. Said out loud, with the
        // way out named: the picker has an unload action and nothing else on screen would
        // have told the user that.
        if (engine.loadedModel?.modelPath == model.file.absolutePath) {
            local.update {
                it.copy(
                    notice = context.getString(R.string.model_delete_loaded, model.name),
                )
            }
            return
        }
        // Refuse to hide the primary model if its large companion cannot be removed.
        // This keeps a failed cleanup visible and retryable from the same row.
        if (model.projector?.let { it.exists() && !it.delete() } == true) {
            local.update {
                it.copy(notice = context.getString(R.string.model_delete_failed, model.name))
            }
            return
        }
        model.projector?.sourceSidecar?.delete()
        if (!model.file.delete()) {
            local.update {
                it.copy(notice = context.getString(R.string.model_delete_failed, model.name))
            }
            return
        }
        modelStore.forgetPublisher(model.file.name)
        model.file.sourceSidecar.delete()
        // A compiled model's tokenizer is useless without it and invisible in the list,
        // so leaving it behind would quietly keep megabytes with no way to reclaim them.
        modelStore.tokenizerFor(model.file.name).delete()
        // The projector is useless without its model and is often the larger of the two,
        // so leaving it behind would quietly keep hundreds of megabytes.
        refresh()
    }

    /**
     * Turns WorkManager's view of the queue into rows.
     *
     * Everything the row needs before the worker has run once comes from tags, because
     * input data is not readable from a [WorkInfo] and the row has to say which model it is
     * fetching while the work is still waiting for a network.
     */
    private fun List<WorkInfo>.toDownloads(hidden: Set<String>): List<ActiveDownload> = this
        .mapNotNull { info ->
            val key = info.tagValue(ModelDownloadWorker.TAG_KEY) ?: return@mapNotNull null
            if (key in hidden) return@mapNotNull null

            val row = ActiveDownload(
                repoId = info.tagValue(ModelDownloadWorker.TAG_REPO).orEmpty(),
                path = info.tagValue(ModelDownloadWorker.TAG_PATH).orEmpty(),
                key = key,
            )

            when (info.state) {
                // Gone from the list either way: a finished file belongs in the model list
                // above, and a cancelled one was asked to disappear.
                WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> null

                WorkInfo.State.FAILED -> row.copy(
                    error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                        ?: "The download could not be completed.",
                )

                else -> row.copy(
                    bytesDone = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_DONE, 0L),
                    // The declared size until the worker reports its own, so a queued
                    // download still shows what it is about to cost.
                    bytesTotal = info.progress.getLong(
                        ModelDownloadWorker.KEY_BYTES_TOTAL,
                        info.tagValue(ModelDownloadWorker.TAG_SIZE)?.toLongOrNull() ?: 0L,
                    ),
                    isVerifying = info.progress.getBoolean(
                        ModelDownloadWorker.KEY_VERIFYING,
                        false,
                    ),
                )
            }
        }
        // Replacing a finished run leaves its record behind for a moment, so the same file
        // can appear twice. The one still going is the one worth showing.
        .groupBy { it.key }
        .map { (_, rows) -> rows.firstOrNull { it.error == null } ?: rows.first() }

    private fun WorkInfo.tagValue(prefix: String): String? =
        tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    private companion object {
        /** Long enough that a rotation does not tear down the query and rebuild it. */
        const val SUBSCRIBE_GRACE_MILLIS = 5_000L

        /** A dropped connection is usually back well inside a minute. */
        const val BACKOFF_SECONDS = 30L
    }
}

/** Downloader provenance belongs to the model and must not survive its deletion. */
private val File.sourceSidecar: File get() = File(parentFile, name + ".source")

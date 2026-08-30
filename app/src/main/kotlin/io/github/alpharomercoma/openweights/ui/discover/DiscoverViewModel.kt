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
import io.github.alpharomercoma.openweights.core.common.model.ExecuTorchFileName
import io.github.alpharomercoma.openweights.core.common.model.GgufMetadata
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.data.UsageRepository
import io.github.alpharomercoma.openweights.core.data.db.ModelDecodeSpeed
import io.github.alpharomercoma.openweights.core.data.db.ModelPrefillSpeed
import io.github.alpharomercoma.openweights.core.device.DeviceProfiler
import io.github.alpharomercoma.openweights.core.device.FitEstimator
import io.github.alpharomercoma.openweights.core.device.FitReport
import io.github.alpharomercoma.openweights.core.device.ThroughputCalibration
import io.github.alpharomercoma.openweights.core.engine.EngineArchitectures
import io.github.alpharomercoma.openweights.core.engine.ExecuTorchSupport
import io.github.alpharomercoma.openweights.core.hub.HubFile
import io.github.alpharomercoma.openweights.core.hub.HubModel
import io.github.alpharomercoma.openweights.core.hub.HubModelDetail
import io.github.alpharomercoma.openweights.core.hub.HubQuery
import io.github.alpharomercoma.openweights.core.hub.HubRuntime
import io.github.alpharomercoma.openweights.core.hub.HubSearchPage
import io.github.alpharomercoma.openweights.core.hub.HubSort
import io.github.alpharomercoma.openweights.core.hub.HuggingFaceClient
import io.github.alpharomercoma.openweights.core.hub.ParameterRange
import io.github.alpharomercoma.openweights.core.hub.Publishers
import io.github.alpharomercoma.openweights.core.hub.RangeByteSource
import io.github.alpharomercoma.openweights.core.hub.gguf.GgufHeaderParser
import io.github.alpharomercoma.openweights.model.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject

/** One downloadable file with everything known about how it would run here. */
data class InspectedFile(
    val file: HubFile,
    val metadata: GgufMetadata? = null,
    val fit: FitReport? = null,
    val isInspecting: Boolean = false,
    val inspectionError: String? = null,
    val isDownloaded: Boolean = false,
    /**
     * The architecture this file declares, when the engine in this build cannot load it.
     *
     * Read from the GGUF header rather than from the Hub's summary, because the header is
     * the file that would actually be downloaded and the summary is derived. Null covers
     * both "supported" and "not read yet", which is deliberate: a check that has not run
     * must not withhold a download.
     */
    val unsupportedArchitecture: String? = null,

    /**
     * The architecture this file declares, when it is a draft head rather than a model.
     *
     * Kept apart from [unsupportedArchitecture] because the two want opposite sentences.
     * An architecture this build does not know is a reason to update the app; a draft is
     * something no version of this app will ever run on its own, and telling somebody to
     * update would send them looking for a release that is never coming.
     */
    val draftArchitecture: String? = null,
)

data class DiscoverUiState(
    val query: HubQuery = HubQuery(),
    val results: List<HubModel> = emptyList(),
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
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
    /** Publisher name to avatar URL, filled in as the lookups come back. */
    val avatars: Map<String, String> = emptyMap(),
) {
    companion object {
        /** The same default a model is loaded with, so the estimate matches the load. */
        const val DEFAULT_CONTEXT = ModelLoadParams.DEFAULT_CONTEXT_LENGTH
    }
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val client: HuggingFaceClient,
    private val profiler: DeviceProfiler,
    private val estimator: FitEstimator,
    private val rangeSourceFactory: RangeByteSource.Factory,
    private val modelStore: ModelStore,
    private val publishers: Publishers,
    private val usageRepository: UsageRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var nextCursor: String? = null
    private var searchGeneration = 0L

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
        // The ceiling is worked out here whether or not it is applied, because the size
        // chip has to be able to say "Under 10B" the moment somebody reaches for it.
        val ceiling = estimator.parameterCeilingBillions(profiler.profile())
        _uiState.update { it.copy(parameterCeilingBillions = ceiling) }
        loadCalibration()
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

    /**
     * Switches which runtime's models are being browsed.
     *
     * Clears the results rather than filtering them, because the two searches reach
     * different corners of the Hub and blending a stale page of one into the other would
     * show models the current runtime cannot open. Only reachable in a build carrying both
     * runtimes; see [ExecuTorchSupport].
     */
    fun onRuntimeChange(runtime: HubRuntime) {
        if (!ExecuTorchSupport.AVAILABLE || _uiState.value.query.runtime == runtime) return
        onQueryChange(
            _uiState.value.query.copy(
                runtime = runtime,
                // The shortlist is GGUF repositories by name, so it means nothing here.
                recommendedOnly = runtime == HubRuntime.LLAMA_CPP,
            ),
        )
    }

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

    /**
     * Only what an organisation published, or everything.
     *
     * The Hub has no search parameter for this, so it is applied to the results instead,
     * which is why it re-runs the search rather than filtering what is already on screen:
     * a page of thirty that loses twenty is a page of ten, and the next page is where the
     * rest of the organisations are.
     */
    fun onOfficialOnlyChange(enabled: Boolean) =
        onQueryChange(_uiState.value.query.copy(officialOnly = enabled))

    /**
     * The measured shortlist, or the whole Hub.
     *
     * Turning it off brings the size ceiling on, because arriving at the unfiltered Hub
     * sorted by trending is arriving at models nothing in a pocket can hold, and because
     * the chip says "Under 10B" once it is on, so the narrowing is on screen and one tap
     * from being undone.
     *
     * It used to switch Official on at the same time, and that was a trap. Official is the
     * fourth chip in a row that scrolls, so on a 360dp phone it is past the right edge:
     * leaving the shortlist silently enabled a filter the user could not see, and the two
     * lit chips they could see were the only two they knew to turn off. What Official
     * removes is every account the Hub calls a person, which for any model released this
     * month is everybody who has converted it, so the result was an empty screen with no
     * visible cause. A filter this app turns on by itself has to be one the user can see.
     */
    fun onRecommendedOnlyChange(enabled: Boolean) = onQueryChange(
        _uiState.value.query.copy(
            recommendedOnly = enabled,
            maxParametersBillions = _uiState.value.parameterCeilingBillions
                .takeIf { !enabled && it > 0 },
        ),
    )

    fun search() {
        searchJob?.cancel()
        nextCursor = null
        val generation = ++searchGeneration
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSearching = true,
                    isLoadingMore = false,
                    canLoadMore = false,
                    results = emptyList(),
                    error = null,
                )
            }
            val query = _uiState.value.query
            runCatching {
                when {
                    // A shortlist is fetched by name, not searched for. Typing in the box
                    // means the shortlist is not what is being asked for any more.
                    query.recommendedOnly && query.text.isBlank() ->
                        HubSearchPage(client.recommended())
                    query.officialOnly -> officialPage(query, cursor = null)
                    else -> client.searchPage(query)
                }
            }
                .onSuccess { results ->
                    if (generation != searchGeneration || _uiState.value.query != query) {
                        return@onSuccess
                    }
                    val page = results
                    val models = page.models
                    nextCursor = page.cursor
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            results = models,
                            canLoadMore = page.hasMore,
                        )
                    }
                    resolveAvatars(models)
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    _uiState.update {
                        it.copy(isSearching = false, error = failure.readableMessage())
                    }
                }
        }
    }

    /** Appends one page for an ordinary Hub search, preserving the current query generation. */
    fun loadMore() {
        val state = _uiState.value
        val isRecommendedSearch = state.query.recommendedOnly && state.query.text.isBlank()
        if (state.isSearching) return
        if (state.isLoadingMore) return
        if (!state.canLoadMore) return
        if (isRecommendedSearch) return
        searchJob?.cancel()
        val generation = searchGeneration
        searchJob = viewModelScope.launch {
            val query = _uiState.value.query
            _uiState.update { it.copy(isLoadingMore = true, error = null) }
            runCatching {
                if (query.officialOnly) {
                    officialPage(query, nextCursor)
                } else {
                    client.searchPage(query, cursor = nextCursor)
                }
            }
                .onSuccess { page ->
                    if (generation != searchGeneration || _uiState.value.query != query) {
                        return@onSuccess
                    }
                    val existing = _uiState.value.results.mapTo(linkedSetOf()) { it.id }
                    val pageModels = page.models
                    val appended = pageModels.filter { existing.add(it.id) }
                    nextCursor = page.cursor
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            results = it.results + appended,
                            canLoadMore = page.hasMore,
                        )
                    }
                    resolveAvatars(appended)
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    _uiState.update {
                        it.copy(isLoadingMore = false, error = failure.readableMessage())
                    }
                }
        }
    }

    /**
     * The results whose publisher is an organisation rather than a person.
     *
     * Resolved before the results are published rather than after, so the list appears
     * already filtered. Filtering what is already on screen would show a page and then
     * take two thirds of it away, which reads as a bug however correct it is.
     *
     * Concurrent, and cached across searches, so the cost is one round trip per publisher
     * this session and nothing at all on the second search.
     */
    private suspend fun official(results: List<HubModel>): List<HubModel> = coroutineScope {
        val kinds = results.map { it.owner }.filter { it.isNotEmpty() }.distinct()
            .map { owner ->
                async { owner to runCatching { publishers.lookUp(owner) }.getOrNull() }
            }
            .awaitAll()
            .toMap()
        results.filter { kinds[it.owner]?.isOrganisation == true }
    }

    /** Skips empty client-filtered pages so infinite scroll always gets a visible trigger. */
    private suspend fun officialPage(query: HubQuery, cursor: String?): HubSearchPage {
        var next = cursor
        val visited = mutableSetOf<String?>()
        repeat(MAX_EMPTY_OFFICIAL_PAGES + 1) {
            if (!visited.add(next)) return HubSearchPage(emptyList(), cursor = null)
            val page = client.searchPage(query, cursor = next)
            val models = official(page.models)
            next = page.cursor
            if (models.isNotEmpty() || !page.hasMore) return HubSearchPage(models, next)
        }
        return HubSearchPage(emptyList(), next)
    }

    /**
     * Looks up the picture for each publisher in the results.
     *
     * One lookup per distinct name, and only for names not already resolved, because a
     * page of GGUF repositories is usually a handful of prolific publishers. Each result
     * is published as it lands rather than waiting for the set, so the fast ones appear
     * while a slow one is still in flight. Failures are silent: the row already draws
     * initials, and a missing picture is not news.
     */
    private fun resolveAvatars(results: List<HubModel>) {
        val wanted = results.map { it.owner }
            .filter { it.isNotEmpty() && it !in _uiState.value.avatars }
            .distinct()

        wanted.forEach { owner ->
            viewModelScope.launch {
                val url = runCatching { publishers.lookUp(owner) }.getOrNull()?.avatarUrl
                    ?: return@launch
                _uiState.update { it.copy(avatars = it.avatars + (owner to url)) }
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

                    // A repository offers one or the other. Compiled weights are listed
                    // only when the tokenizer is there too, because a `.pte` without one
                    // downloads fine and then cannot open, and only in a build that has a
                    // runtime for them.
                    val compiled = detail.compiled
                        .takeIf { ExecuTorchSupport.AVAILABLE && detail.isInstallableCompiled }
                        .orEmpty()
                    val installedName = ExecuTorchFileName.modelNameFor(repoId)

                    _uiState.update { state ->
                        state.copy(
                            detail = detail,
                            files = detail.files.map { file ->
                                InspectedFile(
                                    file = file,
                                    isDownloaded = file.path.substringAfterLast('/') in downloaded,
                                )
                            } + compiled.map { file ->
                                InspectedFile(
                                    file = file,
                                    // Named after the repository once installed, so that is
                                    // what says whether it is already here.
                                    isDownloaded = installedName in downloaded,
                                )
                            },
                        )
                    }
                    coroutineScope {
                        // GGUF only. Inspection reads a header over the network with range
                        // requests; a `.pte` has no header to read, and asking would spend
                        // requests to learn nothing. Its row shows what the Hub said and
                        // no fit estimate, which is honest rather than blank-because-broken.
                        detail.files.forEach { file -> launch { inspect(repoId, file) } }
                    }
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
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
            // The one question the fit report cannot answer. Memory says whether there is
            // room for the weights; this says whether the engine in this APK knows the
            // shape of them at all, and a model released after this build was cut is the
            // case where the answer is no. Asked here, over a couple of kilobytes of
            // header, rather than after several gigabytes of download.
            //
            // A draft head reads as unsupported here, because it is dropped from the
            // supported set for the same reason `clip` is, so it has to be told apart
            // before the unsupported branch claims it: both are files this build will not
            // run, and only one of them is waiting on a newer build.
            val draft = metadata.architecture.takeIf { EngineArchitectures.isDraft(it) }
            val architecture = metadata.architecture
                .takeUnless { EngineArchitectures.supports(it) || draft != null }
            updateFile(file.path) {
                it.copy(
                    isInspecting = false,
                    metadata = metadata,
                    fit = fit,
                    unsupportedArchitecture = architecture,
                    draftArchitecture = draft,
                )
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
            calibration = calibration,
            prefillCalibration = prefillCalibration,
            projectorSizeBytes = _uiState.value.detail?.pairedProjector(file)?.sizeBytes ?: 0,
        )

    /**
     * What this device has actually measured, if anything, read once per visit to this
     * screen rather than per file: it does not change while someone is browsing, and
     * [estimate] runs synchronously from inside a plain state update, which a suspending
     * read cannot.
     *
     * FitEstimator predicts a new file's speed from one real (bytes, tokens a second) pair
     * on this device, because decode is bandwidth-bound and one measurement predicts other
     * sizes far better than a table of chip names. The pair was never supplied here, so the
     * whole estimate — the formula, the test, the line in [FitCard] — has sat unreachable:
     * a browsing screen showing memory but never speed. This is where it was missing from.
     *
     * Built from [UsageRepository.decodeSpeedByModel], not [UsageRepository.observeSummary],
     * and that distinction is the one that matters: the summary's average divides by prefill
     * time as well as decode time, which reads as this device running dramatically slower
     * than it does the moment a session's prompts have gotten long — measured live, LFM2.5
     * at a genuine ~30 tokens a second read back as low as 3 once a long research goal's
     * prompts were folded into the average. Decode speed alone is what a new model's decode
     * speed should be predicted from.
     *
     * The heaviest-used model this device has actually run, since that average is the one
     * least likely to be a single unrepresentative reply. A model since deleted from disk
     * cannot be sized, so it is skipped in favour of the next one down the list rather than
     * reported against a size that is now a guess.
     */
    private var calibration: ThroughputCalibration? = null

    /** The prefill mirror of [calibration]. See [matchPrefillCalibration]. */
    private var prefillCalibration: ThroughputCalibration? = null

    private fun loadCalibration() {
        viewModelScope.launch {
            val decodeSpeeds = usageRepository.decodeSpeedByModel()
            val prefillSpeeds = usageRepository.prefillSpeedByModel()
            val installed = modelStore.availableModels().associateBy { it.nameWithoutExtension }
            calibration = matchCalibration(decodeSpeeds, installed)
            prefillCalibration = matchPrefillCalibration(prefillSpeeds, installed)
        }
    }

    private fun updateFile(path: String, transform: (InspectedFile) -> InspectedFile) {
        _uiState.update { state ->
            state.copy(
                files = state.files.map { if (it.file.path == path) transform(it) else it },
            )
        }
    }

    private companion object {
        const val MAX_CONCURRENT_INSPECTIONS = 3
        const val MAX_EMPTY_OFFICIAL_PAGES = 3

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

/**
 * The heaviest-used model this device has actually run that is still installed, turned
 * into the one (bytes, tokens a second) pair [FitEstimator] needs.
 *
 * [decodeSpeeds] is already sorted by generated tokens descending, so the first match here
 * is the calibration with the most data behind it, not merely the most recent. A model
 * whose usage this device remembers but whose file has since been deleted is skipped
 * rather than reported against a size that would now be a guess.
 */
internal fun matchCalibration(
    decodeSpeeds: List<ModelDecodeSpeed>,
    installed: Map<String, File>,
): ThroughputCalibration? = decodeSpeeds.firstNotNullOfOrNull { model ->
    val file = installed[model.modelName] ?: return@firstNotNullOfOrNull null
    ThroughputCalibration(
        measuredBytes = file.length(),
        measuredTokensPerSecond = model.averageTokensPerSecond,
    )
}

/** The prefill mirror of [matchCalibration], from [UsageRepository.prefillSpeedByModel]. */
internal fun matchPrefillCalibration(
    prefillSpeeds: List<ModelPrefillSpeed>,
    installed: Map<String, File>,
): ThroughputCalibration? = prefillSpeeds.firstNotNullOfOrNull { model ->
    val file = installed[model.modelName] ?: return@firstNotNullOfOrNull null
    ThroughputCalibration(
        measuredBytes = file.length(),
        measuredTokensPerSecond = model.averageTokensPerSecond,
    )
}

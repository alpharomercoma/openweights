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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.model.ModelFormat
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.designsystem.component.Caption
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.component.StepSlider
import io.github.alpharomercoma.openweights.core.designsystem.component.formatBytes
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.engine.EngineArchitectures
import io.github.alpharomercoma.openweights.core.engine.ExecuTorchSupport
import io.github.alpharomercoma.openweights.core.hub.HubModel
import io.github.alpharomercoma.openweights.core.hub.HubQuery
import io.github.alpharomercoma.openweights.core.hub.HubRuntime
import io.github.alpharomercoma.openweights.core.hub.HubSort
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("CyclomaticComplexMethod") // One screen owns list, filters, detail, and back states.
fun DiscoverScreen(
    state: DiscoverUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit = {},
    onSortChange: (HubSort) -> Unit,
    onRuntimeToggled: (HubRuntime, Boolean) -> Unit,
    onFiltersChange: (HubQuery) -> Unit,
    onPhoneSizedChange: (Boolean) -> Unit,
    onOfficialOnlyChange: (Boolean) -> Unit,
    onRecommendedOnlyChange: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onOpenModel: (String) -> Unit,
    onCloseModel: () -> Unit,
    onContextLengthChange: (Int) -> Unit,
    onDownload: (String, String) -> Unit,
    /** Downloads already running, by destination filename, so a started one says so. */
    downloading: Map<String, Float> = emptyMap(),
    /**
     * Pops back to the conversation, when this screen was pushed from it.
     *
     * Nullable so the arrow only appears where there is somewhere to go back to, which is
     * also what keeps every existing caller and every screen test compiling while the
     * navigation is being rebuilt around it.
     */
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LoadMoreEffect(listState, state.results.size, state.canLoadMore, onLoadMore)

    // The system back gesture, which the toolbar arrow has always handled correctly and this
    // did not. Opening a model replaces the list with its files rather than pushing a route,
    // so back went to the NavHost, popped Discover entirely, and left somebody who was
    // choosing a quantisation looking at the conversation. Here it means the same thing the
    // arrow means: close the model first, and only leave once the list is showing.
    BackHandler(enabled = state.detail != null) { onCloseModel() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The top bar applies the status-bar inset itself and the app's navigation bar owns
        // the bottom one, so this scaffold must not add either, doing both is what left the
        // chrome floating away from the edges it belongs to.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.detail?.model?.name ?: stringResource(R.string.discover),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                // One arrow, two meanings, in the order a person would expect: while a
                // model is open it closes the model, and only once the list is showing
                // does it leave the screen. Two separate arrows would be two ways out of
                // a place with one way in.
                navigationIcon = {
                    val back: (() -> Unit)? = when {
                        state.detail != null -> onCloseModel
                        else -> onBack
                    }
                    back?.let { pop ->
                        IconButton(onClick = pop) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = if (state.detail != null) {
                                    stringResource(R.string.back_to_search_results)
                                } else {
                                    stringResource(R.string.back)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            if (state.detail != null) {
                ModelDetail(
                    state = state,
                    onContextLengthChange = onContextLengthChange,
                    onDownload = { path -> onDownload(state.detail.model.id, path) },
                    downloading = downloading,
                )
                return@Scaffold
            }

            OutlinedTextField(
                value = state.query.text,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.search_hugging_face)) },
                singleLine = true,
                shape = RoundedCornerShape(Radius.md),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { onSearch() },
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )

            DiscoverFilterBar(
                query = state.query,
                parameterCeilingBillions = state.parameterCeilingBillions,
                onSortChange = onSortChange,
                onPhoneSizedChange = onPhoneSizedChange,
                onOfficialOnlyChange = onOfficialOnlyChange,
                onRecommendedOnlyChange = onRecommendedOnlyChange,
                onOpenFilters = { filtersOpen = true },
                onRuntimeToggled = onRuntimeToggled,
                showRuntime = ExecuTorchSupport.AVAILABLE,
            )

            if (state.isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.results.isEmpty() && !state.isSearching && state.error == null) {
                EmptyResults(
                    hasFilters = state.query.activeCount > 0,
                    canContinue = state.canLoadMore,
                    onContinue = onLoadMore,
                    onClearFilters = onClearFilters,
                )
            }

            // One container with rules in it rather than a card per model. Five free
            // floating cards read as five unrelated things and gave a list of search
            // results the same shape as the settings screen; a list looks like a list.
            LazyColumn(state = listState, contentPadding = PaddingValues(16.dp)) {
                if (state.query.text.isBlank()) {
                    if (state.results.isNotEmpty()) {
                        item(key = "models-heading") {
                            Text(
                                text = stringResource(R.string.language_models_heading),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 4.dp,
                                    top = 10.dp,
                                    bottom = 6.dp,
                                ),
                            )
                        }
                    }
                }

                itemsIndexed(state.results, key = { _, model -> model.id }) { index, model ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(resultShape(index, state.results.lastIndex))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        if (index > 0) {
                            HorizontalDivider(
                                thickness = Dp.Hairline,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = 14.dp),
                            )
                        }
                        ModelRow(
                            model = model,
                            onClick = { onOpenModel(model.id) },
                            avatarUrl = state.avatars[model.owner],
                        )
                    }
                }
                if (state.isLoadingMore) {
                    item(key = "loading-more") {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    if (filtersOpen) {
        DiscoverFilterSheet(
            query = state.query,
            parameterCeilingBillions = state.parameterCeilingBillions,
            onQueryChange = onFiltersChange,
            onClear = onClearFilters,
            onDismiss = { filtersOpen = false },
        )
    }
}

private const val LOAD_MORE_THRESHOLD = 4

@Composable
private fun LoadMoreEffect(
    listState: LazyListState,
    resultCount: Int,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(listState, resultCount, canLoadMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .filter { it >= (resultCount - 1 - LOAD_MORE_THRESHOLD).coerceAtLeast(0) }
            .collectLatest { onLoadMore() }
    }
}

/**
 * Round the outside of the list and nothing inside it.
 *
 * Each row is its own lazy item, so the container cannot be one shape around all of them;
 * the corners belong to the first and last rows instead. A single result gets all four.
 */
private fun resultShape(index: Int, last: Int) = RoundedCornerShape(
    topStart = if (index == 0) Radius.md else 0.dp,
    topEnd = if (index == 0) Radius.md else 0.dp,
    bottomStart = if (index == last) Radius.md else 0.dp,
    bottomEnd = if (index == last) Radius.md else 0.dp,
)

/** What to say when the Hub has nothing, which on a narrow filter is most of the time. */
@Composable
private fun EmptyResults(
    hasFilters: Boolean,
    canContinue: Boolean,
    onContinue: () -> Unit,
    onClearFilters: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                if (hasFilters) R.string.no_filter_matches else R.string.no_models_found,
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = if (hasFilters) {
                stringResource(R.string.try_wider_filters)
            } else {
                stringResource(R.string.try_different_search)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasFilters) {
            TextButton(onClick = onClearFilters) { Text(stringResource(R.string.clear_filters)) }
        }
        if (canContinue) {
            TextButton(onClick = onContinue) { Text(stringResource(R.string.continue_search)) }
        }
    }
}

@Composable
private fun ModelDetail(
    state: DiscoverUiState,
    onContextLengthChange: (Int) -> Unit,
    onDownload: (String) -> Unit,
    downloading: Map<String, Float> = emptyMap(),
) {
    val detail = state.detail ?: return

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text(detail.model.id, style = MaterialTheme.typography.titleMedium)
                Metric(
                    listOfNotNull(
                        detail.license?.let { stringResource(R.string.license_label, it) },
                        detail.architecture,
                        detail.parameterCount?.let {
                            stringResource(R.string.parameter_count_millions, it / 1_000_000)
                        },
                    ).joinToString(" · "),
                )
            }
        }

        // Said at the top, from the Hub's own summary, so it arrives with the screen
        // rather than after every file header has been read over the network. The cards
        // below repeat it per file from the header itself, which is the authority; this is
        // the early warning, and on a repository the Hub has no summary for it stays quiet
        // and the cards answer alone.
        detail.architecture?.takeUnless { EngineArchitectures.supports(it) }?.let { arch ->
            item {
                Callout(
                    stringResource(R.string.unsupported_architecture_detail, arch),
                )
            }
        }

        detail.defaultProjector()?.let { projector ->
            item {
                // Said before the download rather than discovered after it: the projector
                // is a second file, it is counted in the fit report above, and on a small
                // vision model it can be larger than the model itself.
                Callout(
                    stringResource(
                        R.string.vision_projector_detail,
                        formatBytes(projector.sizeBytes),
                    ),
                )
            }
        }

        // The slider only moves the maths for GGUF files: their KV cache scales with
        // context, so the same file can be comfortable at 4k and impossible at 64k. A
        // compiled graph's window was fixed at export, so on a repository with nothing but
        // compiled files the slider would be a control that changes nothing — say the fact
        // instead of offering the dead control.
        if (state.files.any { ModelFormat.of(it.file.fileName) == ModelFormat.GGUF }) {
            item {
                Column {
                    Caption(
                        stringResource(R.string.context_length_tokens, state.contextLength),
                    )
                    StepSlider(
                        value = state.contextLength.toFloat(),
                        onValueChange = { onContextLengthChange(it.roundToInt()) },
                        valueRange = CONTEXT_RANGE,
                        steps = ModelLoadParams.CONTEXT_STEPS,
                    )
                }
            }
        } else if (state.files.isNotEmpty()) {
            item {
                Caption(stringResource(R.string.context_fixed_at_export))
            }
        }

        items(state.files, key = { it.file.path }) { inspected ->
            FitCard(
                inspected = inspected,
                onDownload = { onDownload(inspected.file.path) },
                downloadFraction = downloading[inspected.file.fileName],
            )
        }
    }
}

/** A short standing fact about the repository, distinct from the per-file fit cards. */
@Composable
private fun Callout(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun DiscoverScreenPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        DiscoverScreen(
            state = DiscoverUiState(
                query = HubQuery(text = stringResource(R.string.lfm2_5)),
                parameterCeilingBillions = 11,
                results = listOf(
                    HubModel(
                        id = "LiquidAI/LFM2.5-VL-1.6B-GGUF",
                        downloads = 68_468,
                        likes = 205,
                        isGated = false,
                        tags = emptyList(),
                        updatedAt = null,
                        pipelineTag = "image-text-to-text",
                    ),
                    HubModel(
                        id = "unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF",
                        downloads = 5_900_530,
                        likes = 882,
                        isGated = false,
                        tags = emptyList(),
                        updatedAt = null,
                        pipelineTag = "text-generation",
                    ),
                ),
            ),
            onQueryChange = {},
            onSearch = {},
            onSortChange = {},
            onFiltersChange = {},
            onPhoneSizedChange = {},
            onOfficialOnlyChange = {},
            onRecommendedOnlyChange = {},
            onRuntimeToggled = { _, _ -> },
            onClearFilters = {},
            onOpenModel = {},
            onCloseModel = {},
            onContextLengthChange = {},
            onDownload = { _, _ -> },
        )
    }
}

/** The context lengths a user may pick, as the slider wants them. */
private val CONTEXT_RANGE =
    ModelLoadParams.MIN_CONTEXT_LENGTH.toFloat()..ModelLoadParams.MAX_CONTEXT_LENGTH.toFloat()

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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.hub.HubModel
import io.github.alpharomercoma.openweights.core.hub.HubQuery
import io.github.alpharomercoma.openweights.core.hub.HubSort
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: DiscoverUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSortChange: (HubSort) -> Unit,
    onFiltersChange: (HubQuery) -> Unit,
    onPhoneSizedChange: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onOpenModel: (String) -> Unit,
    onCloseModel: () -> Unit,
    onContextLengthChange: (Int) -> Unit,
    onDownload: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }

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
                        text = state.detail?.model?.name ?: "Discover",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    if (state.detail != null) {
                        IconButton(onClick = onCloseModel) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back to search results",
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.detail != null) {
                ModelDetail(
                    state = state,
                    onContextLengthChange = onContextLengthChange,
                    onDownload = { path -> onDownload(state.detail.model.id, path) },
                )
                return@Scaffold
            }

            OutlinedTextField(
                value = state.query.text,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search Hugging Face") },
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
                onOpenFilters = { filtersOpen = true },
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
                    onClearFilters = onClearFilters,
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.results, key = { it.id }) { model ->
                    ModelRow(model = model, onClick = { onOpenModel(model.id) })
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

/** What to say when the Hub has nothing, which on a narrow filter is most of the time. */
@Composable
private fun EmptyResults(hasFilters: Boolean, onClearFilters: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (hasFilters) "Nothing matches those filters" else "No models found",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = if (hasFilters) {
                "Try a wider size band, or clear the filters and search again."
            } else {
                "Try a different search term."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasFilters) {
            TextButton(onClick = onClearFilters) { Text("Clear filters") }
        }
    }
}

@Composable
private fun ModelDetail(
    state: DiscoverUiState,
    onContextLengthChange: (Int) -> Unit,
    onDownload: (String) -> Unit,
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
                        detail.license?.let { "license $it" },
                        detail.architecture,
                        detail.parameterCount?.let { "${it / 1_000_000} M params" },
                    ).joinToString(" · "),
                )
            }
        }

        detail.defaultProjector()?.let { projector ->
            item {
                // Said before the download rather than discovered after it: the projector
                // is a second file, it is counted in the fit report above, and on a small
                // vision model it can be larger than the model itself.
                Callout(
                    "This model can read images. Its ${formatBytes(projector.sizeBytes)} " +
                        "vision encoder downloads with it, and the estimates below already " +
                        "count it.",
                )
            }
        }

        item {
            Column {
                // The slider is the point: KV cache scales with context, so the same file
                // can be comfortable at 4k and impossible at 64k. Changing it re-runs the
                // maths locally, with no further network use.
                Metric("Context length: ${state.contextLength} tokens")
                Slider(
                    value = state.contextLength.toFloat(),
                    onValueChange = { onContextLengthChange(it.roundToInt()) },
                    valueRange = MIN_CONTEXT..MAX_CONTEXT,
                    steps = CONTEXT_STEPS,
                )
            }
        }

        items(state.files, key = { it.file.path }) { inspected ->
            FitCard(inspected = inspected, onDownload = { onDownload(inspected.file.path) })
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

private const val MIN_CONTEXT = 1024f
private const val MAX_CONTEXT = 32_768f
private const val CONTEXT_STEPS = 30

@Preview(showBackground = true, backgroundColor = 0xFF0B0D0F)
@Composable
private fun DiscoverScreenPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        DiscoverScreen(
            state = DiscoverUiState(
                query = HubQuery(text = "lfm2.5"),
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
            onClearFilters = {},
            onOpenModel = {},
            onCloseModel = {},
            onContextLengthChange = {},
            onDownload = { _, _ -> },
        )
    }
}

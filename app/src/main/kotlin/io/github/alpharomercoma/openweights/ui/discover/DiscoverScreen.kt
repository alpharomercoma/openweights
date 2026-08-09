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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.hub.HubModel
import io.github.alpharomercoma.openweights.core.hub.HubSort
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: DiscoverUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSortChange: (HubSort) -> Unit,
    onOpenModel: (String) -> Unit,
    onCloseModel: () -> Unit,
    onContextLengthChange: (Int) -> Unit,
    onDownload: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The top bar applies the status-bar inset itself and the app's navigation bar owns
        // the bottom one, so this scaffold must not add either — doing both is what left the
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
                value = state.query,
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

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HubSort.entries.forEach { sort ->
                    FilterChip(
                        selected = state.sort == sort,
                        onClick = { onSortChange(sort) },
                        label = {
                            Text(
                                text = sort.label,
                                style = MaterialTheme.typography.labelMedium,
                                // A chip that wraps is a chip taller than the ones beside
                                // it, which is what made this row ragged at any font scale.
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        // Selection is one of the accent's three jobs, and a chip whose
                        // only selected state is a slightly different grey is a chip whose
                        // state has to be worked out rather than seen.
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = state.sort == sort,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }

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
}

@Composable
private fun ModelRow(model: HubModel, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
    ) {
        Text(text = model.name, style = MaterialTheme.typography.titleSmall)
        Metric(
            buildString {
                append(model.owner)
                append(" · ")
                append("${model.downloads} downloads")
                if (model.isGated) append(" · gated")
            },
        )
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
                query = "lfm2.5",
                results = listOf(
                    HubModel("LiquidAI/LFM2.5-2.6B-GGUF", 68_468, 205, false, emptyList(), null),
                ),
            ),
            onQueryChange = {},
            onSearch = {},
            onSortChange = {},
            onOpenModel = {},
            onCloseModel = {},
            onContextLengthChange = {},
            onDownload = { _, _ -> },
        )
    }
}

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsColors
import io.github.alpharomercoma.openweights.core.hub.HubQuery
import io.github.alpharomercoma.openweights.core.hub.HubRuntime
import io.github.alpharomercoma.openweights.core.hub.HubSort
import io.github.alpharomercoma.openweights.core.hub.HubTask
import io.github.alpharomercoma.openweights.core.hub.ParameterRange
import kotlinx.coroutines.launch

/**
 * The row of controls under the search field.
 *
 * Sort is here because it is always relevant and always one tap. Everything else lives
 * behind the Filters button: on a phone a row of every control is a row nobody reads, and
 * the count on the button is what says whether anything is narrowing the list.
 *
 * "Fits my phone" sits outside the sheet despite being a filter, because it is the reason
 * to use this app rather than the website, and burying the one differentiated control
 * behind a button would be a strange thing to do.
 */
@Composable
fun DiscoverFilterBar(
    query: HubQuery,
    parameterCeilingBillions: Int,
    onSortChange: (HubSort) -> Unit,
    onPhoneSizedChange: (Boolean) -> Unit,
    onOfficialOnlyChange: (Boolean) -> Unit,
    onRecommendedOnlyChange: (Boolean) -> Unit,
    onOpenFilters: () -> Unit,
    onRuntimeToggled: (HubRuntime, Boolean) -> Unit,
    showRuntime: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = onOpenFilters,
                label = {
                    Text(
                        text = if (query.activeCount == 0) {
                            stringResource(R.string.filters)
                        } else {
                            stringResource(R.string.filters_count, query.activeCount)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (query.activeCount == 0) {
                        MaterialTheme.colorScheme.surfaceContainer
                    } else {
                        OpenWeightsColors.Lime
                    },
                    labelColor = if (query.activeCount == 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        OpenWeightsColors.Ink
                    },
                    leadingIconContentColor = if (query.activeCount == 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        OpenWeightsColors.Ink
                    },
                ),
            )

            // First in the row, because it is the only one on when the screen opens and the
            // one that makes the other three unnecessary while it is.
            FilterChip(
                selected = query.recommendedOnly,
                onClick = { onRecommendedOnlyChange(!query.recommendedOnly) },
                label = {
                    Text(
                        text = stringResource(R.string.recommended),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = selectedChipColors(),
                border = chipBorder(query.recommendedOnly),
            )

            val phoneSized = query.maxParametersBillions != null
            FilterChip(
                selected = phoneSized,
                onClick = { onPhoneSizedChange(!phoneSized) },
                label = {
                    Text(
                        // The number is the whole point once it is on: "Fits my phone" is a
                        // claim, "Under 11B" is the claim with its working shown.
                        text = if (phoneSized && parameterCeilingBillions > 0) {
                            "Under ${parameterCeilingBillions}B"
                        } else {
                            "Fits my phone"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Smartphone,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = selectedChipColors(),
                border = chipBorder(phoneSized),
            )

            // Next to "Fits my phone", because the two answer the same kind of question: not
            // what a model is, but whether it is worth your attention. Size is about the
            // hardware, this is about who stands behind the weights.
            FilterChip(
                selected = query.officialOnly,
                onClick = { onOfficialOnlyChange(!query.officialOnly) },
                label = {
                    Text(
                        text = stringResource(R.string.official),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = selectedChipColors(),
                border = chipBorder(query.officialOnly),
            )

            // Directly after Official, because the three leading chips all narrow what is
            // shown while the sorts only reorder it: the narrowing controls belong
            // together. The sorts this pushes past the right edge are still reachable —
            // the row scrolls and the trailing cue below says so.
            if (showRuntime) {
                RuntimeChip(selected = query.runtimes, onToggle = onRuntimeToggled)
            }

            HubSort.entries.forEach { sort ->
                FilterChip(
                    selected = query.sort == sort,
                    onClick = { onSortChange(sort) },
                    label = {
                        Text(
                            text = sort.label,
                            style = MaterialTheme.typography.labelMedium,
                            // A chip that wraps is a chip taller than the ones beside it, which
                            // is what made this row ragged at any font scale.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = sortChipColors(),
                    // Always the neutral outline: the fill is what says which one is on, and a
                    // lime ring around a grey pill is two answers to one question.
                    border = chipBorder(selected = false),
                )
            }
        }

        // The row is intentionally wider than a phone, but a raw scroll container reads as
        // clipped content. Keep a restrained trailing cue only while more chips are hidden;
        // tapping it advances to the end and the content description makes the affordance
        // available to TalkBack without shrinking labels or hiding filters behind a sheet.
        if (scrollState.maxValue > scrollState.value) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(36.dp)
                    .height(IntrinsicSize.Min)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                        ),
                    )
                    .clickable(
                        onClickLabel = stringResource(R.string.show_more_filters),
                        onClick = {
                            scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                        },
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(R.string.show_more_filters),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Which runtimes the Hub is being searched for: one chip, a menu of boxes behind it.
 *
 * Both are ticked to begin with, because a build carrying two runtimes can run models for
 * either, and an empty selection means "all of them" rather than "nothing" — unticking
 * every box shows everything instead of an empty screen the user has to undo. Only shown
 * where there is a choice to make: a build without the ExecuTorch runtime gets no chip.
 */
@Composable
private fun RuntimeChip(selected: Set<HubRuntime>, onToggle: (HubRuntime, Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // An empty set searches every runtime, so the boxes render as all ticked.
    val effective = selected.ifEmpty { HubRuntime.entries.toSet() }
    val narrowed = effective.size < HubRuntime.entries.size
    Box {
        FilterChip(
            selected = narrowed,
            onClick = { open = true },
            label = {
                Text(
                    text = stringResource(R.string.runtime),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            colors = selectedChipColors(),
            border = chipBorder(narrowed),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            HubRuntime.entries.forEach { runtime ->
                val on = runtime in effective
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                when (runtime) {
                                    HubRuntime.LLAMA_CPP -> R.string.runtime_gguf
                                    HubRuntime.EXECUTORCH -> R.string.runtime_executorch
                                },
                            ),
                        )
                    },
                    leadingIcon = { Checkbox(checked = on, onCheckedChange = null) },
                    onClick = { onToggle(runtime, !on) },
                )
            }
        }
    }
}

/** The filters that do not earn a permanent place on screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverFilterSheet(
    query: HubQuery,
    parameterCeilingBillions: Int,
    onQueryChange: (HubQuery) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.filters), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClear, enabled = query.activeCount > 0) {
                    Text(stringResource(R.string.clear_all))
                }
            }

            FilterGroup(
                title = stringResource(R.string.task),
                caption = "Many repositories carry no task at all",
            ) {
                ChoiceChip(
                    label = stringResource(R.string.any),
                    selected = query.task == null,
                    onClick = { onQueryChange(query.copy(task = null)) },
                )
                HubTask.entries.forEach { task ->
                    ChoiceChip(
                        label = task.label,
                        selected = query.task == task,
                        onClick = { onQueryChange(query.copy(task = task)) },
                    )
                }
            }

            FilterGroup(
                title = stringResource(R.string.size),
                caption = if (parameterCeilingBillions > 0) {
                    "Parameter count. This phone can hold about ${parameterCeilingBillions}B " +
                        "at the usual quantization."
                } else {
                    "Parameter count, as the Hub reports it"
                },
            ) {
                ParameterRange.entries.forEach { range ->
                    ChoiceChip(
                        label = range.label,
                        selected = query.maxParametersBillions == null &&
                            query.parameters == range,
                        onClick = {
                            onQueryChange(
                                query.copy(parameters = range, maxParametersBillions = null),
                            )
                        },
                    )
                }
            }

            OutlinedTextField(
                value = query.author.orEmpty(),
                onValueChange = { onQueryChange(query.copy(author = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.publisher)) },
                placeholder = { Text(stringResource(R.string.unsloth_liquidai_bartowski)) },
                singleLine = true,
            )

            FilterGroup(
                title = stringResource(R.string.access),
                caption = "Gated repositories need an approved token",
            ) {
                ChoiceChip(
                    label = stringResource(R.string.open_only),
                    selected = query.hideGated,
                    onClick = { onQueryChange(query.copy(hideGated = !query.hideGated)) },
                )
            }
        }
    }
}

@Composable
private fun FilterGroup(title: String, caption: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
        colors = selectedChipColors(),
        border = chipBorder(selected),
    )
}

/**
 * Selection is one of the accent's three jobs, and a chip whose only selected state is a
 * slightly different grey is a chip whose state has to be worked out rather than seen.
 *
 * Solid lime carrying ink, which is the one recipe every active thing in this app uses, and
 * the same in both themes. It was `primaryContainer`, which is a dimmed lime on the dark
 * canvas and a pale one on paper: two different-looking recipes for one state, and the dark
 * one read as olive rather than as the accent.
 */
@Composable
private fun selectedChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = OpenWeightsColors.Lime,
    selectedLabelColor = OpenWeightsColors.Ink,
    selectedLeadingIconColor = OpenWeightsColors.Ink,
)

/**
 * The sort, which is a choice rather than a narrowing.
 *
 * Quiet on purpose. One of these is always selected, so painting it lime would mean lime
 * appears in this row whatever the user has done, and an accent that is always on says
 * nothing. With three filters now on by default the row came out as four lime pills in a
 * line, which is a lot of shouting for a state nobody chose. Lime here means "this is
 * hiding results from you"; ordering them is not that.
 */
@Composable
private fun sortChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun chipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = selected,
    borderColor = MaterialTheme.colorScheme.outline,
    selectedBorderColor = OpenWeightsColors.Lime,
)

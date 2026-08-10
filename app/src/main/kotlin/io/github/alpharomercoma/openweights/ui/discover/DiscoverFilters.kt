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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.hub.HubQuery
import io.github.alpharomercoma.openweights.core.hub.HubSort
import io.github.alpharomercoma.openweights.core.hub.HubTask
import io.github.alpharomercoma.openweights.core.hub.ParameterRange

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
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onOpenFilters,
            label = {
                Text(
                    text = if (query.activeCount == 0) {
                        "Filters"
                    } else {
                        "Filters ${query.activeCount}"
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
                    MaterialTheme.colorScheme.primaryContainer
                },
                labelColor = if (query.activeCount == 0) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            ),
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
                colors = selectedChipColors(),
                border = chipBorder(query.sort == sort),
            )
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
                Text("Filters", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClear, enabled = query.activeCount > 0) {
                    Text("Clear all")
                }
            }

            FilterGroup(title = "Task", caption = "Many repositories carry no task at all") {
                ChoiceChip(
                    label = "Any",
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
                title = "Size",
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
                label = { Text("Publisher") },
                placeholder = { Text("unsloth, LiquidAI, bartowski") },
                singleLine = true,
            )

            FilterGroup(title = "Access", caption = "Gated repositories need an approved token") {
                ChoiceChip(
                    label = "Open only",
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
 */
@Composable
private fun selectedChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

@Composable
private fun chipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = selected,
    borderColor = MaterialTheme.colorScheme.outline,
    selectedBorderColor = MaterialTheme.colorScheme.primary,
)

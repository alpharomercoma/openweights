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

package io.github.alpharomercoma.openweights.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.common.model.ReasoningEffort

/**
 * How hard to think, in the composer where the message is written.
 *
 * In the composer rather than buried in model settings because it is a per-question
 * choice, not a per-model one: the same model should think hard about a plan and not at
 * all about a greeting, and on a phone that difference is tens of seconds.
 *
 * What it offers depends on what the loaded model's chat template actually reads, asked
 * of the template at load rather than assumed:
 *
 * - templates that honour `reasoning_effort` get the four levels;
 * - templates that only understand being told whether to think at all get a switch, which
 *   is what LFM2.5 has: rendering it with "low" and with "high" produces identical bytes,
 *   so a level picker there would be a control wired to nothing;
 * - templates that do neither get no control, rather than one that does not work.
 */
@Composable
fun ThinkingControl(
    supportsEffort: Boolean,
    supportsThinking: Boolean,
    effort: ReasoningEffort,
    thinking: Boolean,
    enabled: Boolean,
    onEffort: (ReasoningEffort) -> Unit,
    onThinking: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        supportsEffort -> EffortChip(effort, enabled, onEffort, modifier)
        supportsThinking -> ThinkChip(thinking, enabled, onThinking, modifier)
        else -> Unit
    }
}

@Composable
private fun EffortChip(
    effort: ReasoningEffort,
    enabled: Boolean,
    onEffort: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        FilterChip(
            selected = effort != ReasoningEffort.DEFAULT,
            onClick = { open = true },
            enabled = enabled,
            label = {
                Text(
                    // The level is the whole point, so it is the label rather than a state
                    // hidden behind a generic word.
                    text = if (effort == ReasoningEffort.DEFAULT) {
                        "Effort"
                    } else {
                        "Effort: ${effort.label.lowercase()}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            },
            leadingIcon = { ThinkingIcon() },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReasoningEffort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onEffort(option)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * Thinking on or off.
 *
 * A switch rather than a menu of one useful value: the choice here really is binary, and
 * spending a tap to open a menu to make a binary choice is a tap wasted.
 *
 * Three things say which way it is set, because the first attempt said it with only one
 * and that one did not survive contact with a dark theme. A custom leading icon replaces
 * the checkmark Material draws when a chip is selected, so on and off were separated by a
 * faint container tint and nothing else. Now the word changes, the icon changes shape, and
 * the fill changes, and any one of them is enough to read it.
 */
@Composable
private fun ThinkChip(
    thinking: Boolean,
    enabled: Boolean,
    onThinking: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = thinking,
        onClick = { onThinking(!thinking) },
        enabled = enabled,
        label = {
            Text(
                // Named for what happens, not for the setting. "Think" on a switch does
                // not say whether it is a description of the state or an instruction.
                text = if (thinking) "Thinking" else "No thinking",
                style = MaterialTheme.typography.labelMedium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = if (thinking) Icons.Rounded.Check else Icons.Rounded.Bolt,
                contentDescription = null,
                modifier = Modifier.size(ICON.dp),
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier,
    )
}

@Composable
private fun ThinkingIcon() {
    Icon(
        imageVector = Icons.Rounded.Psychology,
        contentDescription = null,
        modifier = Modifier.size(ICON.dp),
    )
}

private const val ICON = 16

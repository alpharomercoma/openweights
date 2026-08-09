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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius

/**
 * Something the user can do to the conversation rather than say to the model.
 *
 * Typing `/` is how developer tools have taught people to find these, and it beats a menu:
 * the list filters as you type and the names are the documentation.
 */
enum class SlashCommand(val trigger: String, val description: String) {
    NEW_CHAT("/new", "Start a fresh conversation"),
    COMPACT("/compact", "Summarize earlier turns to free up context now"),
    REGENERATE("/retry", "Ask the model for a different answer"),
    ;

    companion object {
        /**
         * Commands matching what has been typed, or null when the draft is not a command.
         *
         * A draft only counts as a command while it is a single `/`-prefixed word — once
         * there is a space, the user is writing a message that happens to start with a
         * slash, and hijacking their input would be wrong.
         */
        fun match(draft: String): List<SlashCommand>? {
            if (!draft.startsWith("/") || draft.contains(' ')) return null
            return entries.filter { it.trigger.startsWith(draft, ignoreCase = true) }
        }
    }
}

@Composable
fun SlashCommandPalette(
    commands: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (commands.isEmpty()) return

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        items(commands, key = { it.trigger }) { command ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(command) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Metric(command.trigger)
                    Text(
                        text = command.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0E11)
@Composable
private fun SlashCommandPalettePreview() {
    OpenWeightsTheme(dynamicColor = false) {
        SlashCommandPalette(commands = SlashCommand.entries, onSelect = {})
    }
}

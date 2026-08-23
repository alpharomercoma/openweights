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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.component.Caption
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.component.formatBytes
import io.github.alpharomercoma.openweights.ui.models.LocalModel
import io.github.alpharomercoma.openweights.ui.models.PublisherHeading
import io.github.alpharomercoma.openweights.ui.models.byPublisher

/**
 * Which model is answering, and how to change it.
 *
 * Raised from the model's name in the top bar, which is where ChatGPT, Claude and Gemini all
 * put it, and for the same reason: switching model is a thing you do about the conversation
 * you are in, so it should not take you out of it. A sheet keeps the conversation on screen
 * behind it and closes with a swipe.
 *
 * The list is only what is on the device. Two rows below it lead everywhere else: browsing
 * the Hub, which is what you do when none of yours fit, and managing what you have, which is
 * where a download in flight and a model you no longer want both live. Neither of those is a
 * thing you do often enough to earn a place in the conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<LocalModel>,
    activeName: String?,
    /** Publisher logos, when the Hub has been asked. Empty offline, and that is fine. */
    avatars: Map<String, String> = emptyMap(),
    onSelect: (LocalModel) -> Unit,
    onBrowse: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            if (models.isEmpty()) {
                Text(
                    text = "No models on this phone yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            // The list scrolls inside its own bounds and the two ways out sit under it,
            // always on screen.
            //
            // It used to be one scrolling column with the actions at the bottom, which is
            // the right order to read and the wrong one to reach: somebody with a dozen
            // models had to scroll past all of them to get to "Browse models", and the
            // sheet gave no sign there was anything below. Capping the list is what lets
            // the footer stay put, and the cap is high enough that the common case of two
            // or three models still shows every one without scrolling at all.
            LazyColumn(modifier = Modifier.heightIn(max = LIST_MAX)) {
                // Grouped the same way Manage Models groups them, because they are the same
                // list and a person who learns one order should not have to learn a second.
                models.byPublisher().forEach { group ->
                    group.heading?.let { heading ->
                        item(key = "head:$heading") {
                            PublisherHeading(
                                heading = heading,
                                avatarUrl = group.publisher?.let(avatars::get),
                                // The same inset the rows here use, so the logo and the
                                // model names share a left edge.
                                startPadding = ROW_INSET,
                            )
                        }
                    }
                    items(group.models, key = { it.file.absolutePath }) { model ->
                        ModelRow(
                            model = model,
                            isActive = model.name == activeName,
                            onSelect = { onSelect(model) },
                        )
                    }
                }
            }

            HorizontalDivider(
                thickness = Dp.Hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            SheetAction(
                label = "Browse models",
                detail = "Find one on Hugging Face",
                icon = Icons.Rounded.Search,
                onClick = onBrowse,
            )
            SheetAction(
                label = "Manage models",
                detail = "Downloads, and what to remove",
                icon = Icons.Rounded.Tune,
                onClick = onManage,
            )
        }
    }
}

@Composable
private fun ModelRow(model: LocalModel, isActive: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A tick rather than a radio, because this is a statement about which model is
        // loaded rather than a form to be submitted. The space is held either way so the
        // names stay in one column.
        if (isActive) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "In use",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(TICK),
            )
        } else {
            Spacer(modifier = Modifier.size(TICK))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.titleSmall)
            Metric(formatBytes(model.sizeBytes))
        }
    }
}

@Composable
private fun SheetAction(label: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(TICK))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Caption(detail)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(TICK).clearAndSetSemantics {},
        )
    }
}

/** The tick, the leading icons and the chevron all share it, so three columns line up. */
private val TICK = 20.dp

/**
 * How tall the list of installed models is allowed to get inside the sheet.
 *
 * Roughly five rows. Past that the list scrolls and the actions below it stay where they
 * are, which is the whole point: the way out of this sheet must not be something you have
 * to scroll to find.
 */
private val LIST_MAX = 300.dp

/** The horizontal padding a row in this sheet uses, which the headings match. */
private val ROW_INSET = 20.dp

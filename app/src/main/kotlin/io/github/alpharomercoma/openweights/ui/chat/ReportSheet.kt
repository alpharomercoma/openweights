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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.data.ReportReason
import io.github.alpharomercoma.openweights.core.designsystem.component.AccentButton

/**
 * Reporting a reply, without leaving the app.
 *
 * Play requires this of anything that generates AI content, and it is the only quality
 * signal an app with no telemetry can have. The report is written to the device. Nothing
 * is sent anywhere: there is no server to send it to, and saying otherwise would break the
 * one promise this app makes.
 *
 * The sheet shows exactly what the report will contain before it is filed, because a
 * reporting flow that is vague about what it captures is worse than none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    modelName: String,
    replyText: String,
    onSubmit: (ReportReason, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var note by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.report_reply),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.what_was_wrong),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ReportReason.entries.chunked(REASONS_PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { candidate ->
                        FilterChip(
                            selected = reason == candidate,
                            onClick = { reason = candidate },
                            label = {
                                Text(
                                    text = candidate.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = if (reason == candidate) {
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
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor =
                                MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor =
                                MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedLeadingIconColor =
                                MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.anything_add)) },
                minLines = 2,
            )

            // Shown, not summarised. Someone filing a report is entitled to see the whole
            // of what it captures before they file it.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.saved_device),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    // It used to end "you can read and delete your reports in Settings",
                    // which was a promise about a screen that was never built. The store is
                    // real and this app has no server, so the first half was always true;
                    // the second half was a plan.
                    text = "The model name ($modelName), the reason, your note and the " +
                        "reply. It stays on this phone. There is nowhere to send it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = replyText.take(PREVIEW_CHARS).ifEmpty { "(an empty reply)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                AccentButton(
                    onClick = { reason?.let { onSubmit(it, note) } },
                    enabled = reason != null,
                ) {
                    Text(stringResource(R.string.report))
                }
            }
        }
    }
}

/** Two per row keeps the longest label on one line at the default font scale. */
private const val REASONS_PER_ROW = 2

private const val PREVIEW_CHARS = 400
private const val PREVIEW_LINES = 6

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
import io.github.alpharomercoma.openweights.core.designsystem.component.AccentButton

/** Why a reply was reported. The list a user picks from, and what goes in the text. */
enum class ReportReason(val wireName: String, val label: String) {
    OFFENSIVE("offensive", "Offensive or hateful"),
    SEXUAL("sexual", "Sexual content"),
    DANGEROUS("dangerous", "Dangerous or illegal advice"),
    HARASSMENT("harassment", "Harassment or threats"),
    WRONG("wrong", "Confidently wrong"),
    OTHER("other", "Something else"),
}

/**
 * Reporting a reply, without leaving the app.
 *
 * Play requires this of anything that generates AI content. What it does with the report is
 * the part that took two attempts. The first version wrote a row into a `content_reports`
 * table and stopped there, and nothing ever read that table: no screen listed the rows, no
 * code counted them, and the copy under this sheet once promised a Settings screen to read
 * them that was never built. A report filed into a store with no reader is worse than no
 * report action at all, because the sheet implies somebody is going to look.
 *
 * So the report is assembled here and handed to the system share sheet, and the person who
 * wrote it decides where it goes: a mail, an issue, their own notes, or nowhere. That keeps
 * the promise this app makes, which is that the app itself sends nothing and has nowhere to
 * send it to. A share the user starts, reads, and can cancel is their action, not ours.
 * Nothing is stored either way, which is the right answer for a reported reply plus a note
 * somebody typed once no part of the app was ever going to read it back.
 *
 * The sheet shows exactly what the report will contain before any of that, because a
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
                    text = stringResource(R.string.what_gets_shared),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    // This has been wrong twice, in opposite directions. It used to end
                    // "you can read and delete your reports in Settings", a promise about a
                    // screen that was never built. It then said the report stays on the
                    // phone because there is nowhere to send it, which was true of the app
                    // and read as though the report had a destination. It has one now, and
                    // it is whichever one the reader picks.
                    text = stringResource(R.string.report_contents_detail, modelName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = replyText.take(PREVIEW_CHARS).ifEmpty {
                        stringResource(R.string.an_empty_reply)
                    },
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

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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.designsystem.component.markdownToPlainText

/**
 * Actions for one message, opened by long-pressing it.
 *
 * Copy is the action people reach for constantly and the one to optimise; regenerate
 * only makes sense on a model reply, and only when nothing is currently generating.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    entry: TranscriptEntry,
    canRegenerate: Boolean,
    canEdit: Boolean,
    canBranch: Boolean,
    isSpeaking: Boolean,
    onRegenerate: () -> Unit,
    onToggleReadAloud: () -> Unit,
    /** Puts the question back in the composer and drops everything after it. */
    onEdit: () -> Unit,
    /** Opens a new conversation carrying everything up to and including this turn. */
    onBranch: () -> Unit,
    onReport: () -> Unit,
    /** Opens the uncertainty view for this reply. Absent when nothing was measured. */
    onShowUncertainty: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Fully expanded, like every other sheet here. Half height clipped the last
        // action off the bottom with nothing to say it was there.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // Sized to its content, deliberately. A verticalScroll here makes the column
        // willing to be any height, so the sheet hands it the leftover space and the last
        // action falls off the bottom. Four rows fit; skipPartiallyExpanded is what makes
        // sure they are all shown.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            // The measurements that no longer fit beside the actions. There is room here,
            // and a reader who wants time to first token has already gone looking for it.
            //
            // A panel rather than the run-on sentence this used to be. Five numbers joined
            // by middots wrapped to three lines on a phone and read as a single fact said
            // five ways: nothing in "142.0 tok/s · 36.1 tok/s · 0.41 s · 96 tokens · 4.3 s"
            // says which of those two rates belongs to which half of the turn, or that the
            // 96 is the second one's and not the first's. See [TurnStatsPanel].
            if (entry.hasStats) {
                TurnStatsPanel(
                    entry = entry,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            // The reasoning block is the model talking to itself; copying the answer is what
            // someone pasting into a document wants. Which form of the answer is the other
            // half of the question, and it has two honest answers rather than one: a reply
            // is Markdown source, so the single copy action handed literal asterisks to
            // anything that could not render them, and stripping them unasked would have
            // taken the source away from the editors that can.
            ActionRow(
                icon = Icons.Rounded.ContentCopy,
                label = stringResource(R.string.copy_text),
                onClick = {
                    val answer = entry.answer.ifEmpty { entry.text }
                    context.copyToClipboard(answer.markdownToPlainText())
                    onDismiss()
                },
            )
            ActionRow(
                icon = Icons.Rounded.Code,
                label = stringResource(R.string.copy_text_as_markdown),
                onClick = {
                    context.copyToClipboard(entry.answer.ifEmpty { entry.text })
                    onDismiss()
                },
            )
            if (entry.role == ChatRole.ASSISTANT) {
                // The one output modality a local model can add beyond text: the phone's
                // own synthesiser reading what it wrote, with nothing leaving the device.
                ActionRow(
                    icon = if (isSpeaking) {
                        Icons.Rounded.StopCircle
                    } else {
                        Icons.AutoMirrored.Rounded.VolumeUp
                    },
                    label = stringResource(
                        if (isSpeaking) R.string.stop_reading else R.string.read_aloud,
                    ),
                    onClick = {
                        onToggleReadAloud()
                        onDismiss()
                    },
                )
            }
            if (canRegenerate) {
                ActionRow(
                    icon = Icons.Rounded.Refresh,
                    label = stringResource(R.string.regenerate_reply),
                    onClick = onRegenerate,
                )
            }
            if (canEdit) {
                // Asking again, better. The reply that followed a question is what a person
                // is reacting to when they want to change the question, so editing has to
                // drop it: leaving the old answer under a new prompt would be a transcript
                // of a conversation that never happened.
                ActionRow(
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.edit_resend),
                    onClick = {
                        onEdit()
                        onDismiss()
                    },
                )
            }
            if (canBranch) {
                // The other way to change direction, and the one that keeps both. Everything
                // up to here is copied into a new conversation, so the thread that was going
                // well is still there when the detour does not work out.
                ActionRow(
                    icon = Icons.Rounded.CallSplit,
                    label = stringResource(R.string.branch_from_here),
                    onClick = {
                        onBranch()
                        onDismiss()
                    },
                )
            }
            if (entry.confidence.tokenCount > 0) {
                // Only when there is something to show. The view is off by default, so for
                // most replies this action would open a sheet whose whole content is an
                // explanation of why it is empty.
                ActionRow(
                    icon = Icons.Rounded.Troubleshoot,
                    label = stringResource(R.string.uncertainty_action),
                    onClick = onShowUncertainty,
                )
            }
            if (entry.role == ChatRole.ASSISTANT) {
                // Required of anything that generates AI content, and the only signal this
                // app can have about a model's behaviour when nothing is measured remotely.
                ActionRow(
                    icon = Icons.Rounded.Flag,
                    label = stringResource(R.string.report_reply),
                    onClick = onReport,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("OpenWeights message", text))
}

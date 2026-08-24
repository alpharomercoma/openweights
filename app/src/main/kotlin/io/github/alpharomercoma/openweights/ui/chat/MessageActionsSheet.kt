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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
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
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import java.util.Locale

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
    /** Writes this reply to a PDF wherever the user chooses to put it. */
    onSavePdf: () -> Unit,
    onReport: () -> Unit,
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
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            // The measurements that no longer fit beside the actions. There is room here,
            // and a reader who wants time to first token has already gone looking for it.
            entry.detail()?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            ActionRow(
                icon = Icons.Rounded.ContentCopy,
                label = "Copy text",
                onClick = {
                    // The reasoning block is the model talking to itself; copying the
                    // answer is what someone pasting into a document wants.
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
                    label = if (isSpeaking) "Stop reading" else "Read aloud",
                    onClick = {
                        onToggleReadAloud()
                        onDismiss()
                    },
                )
            }
            if (canRegenerate) {
                ActionRow(
                    icon = Icons.Rounded.Refresh,
                    label = "Regenerate reply",
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
                    label = "Edit and resend",
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
                    label = "Branch from here",
                    onClick = {
                        onBranch()
                        onDismiss()
                    },
                )
            }
            if (entry.role == ChatRole.ASSISTANT) {
                // The one way a reply leaves this app as a document rather than as text on
                // a clipboard. Markdown a model wrote is a report, a plan or an essay often
                // enough that "keep this" should not have to mean "paste it somewhere else".
                ActionRow(
                    icon = Icons.Rounded.PictureAsPdf,
                    label = "Save as PDF",
                    onClick = {
                        onSavePdf()
                        onDismiss()
                    },
                )
                // Required of anything that generates AI content, and the only signal this
                // app can have about a model's behaviour when nothing is measured remotely.
                ActionRow(
                    icon = Icons.Rounded.Flag,
                    label = "Report this reply",
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

/**
 * Everything measured about one reply, for the sheet.
 *
 * Null while a reply is still arriving, because half a measurement is worse than none.
 */
private fun TranscriptEntry.detail(): String? {
    if (tokensPerSecond == null) return null
    val locale = Locale.getDefault()
    return listOfNotNull(
        String.format(locale, "%.1f tok/s", tokensPerSecond),
        timeToFirstTokenMs?.let {
            String.format(
                locale,
                "%.1fs to first token",
                it / MILLIS_PER_SECOND,
            )
        },
        generatedTokens?.let { "$it tokens" },
        totalMillis?.let { String.format(locale, "%.1fs in total", it / MILLIS_PER_SECOND) },
    ).joinToString(" · ")
}

private const val MILLIS_PER_SECOND = 1000.0

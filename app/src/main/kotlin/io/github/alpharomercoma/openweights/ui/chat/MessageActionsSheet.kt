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
import androidx.compose.material.icons.rounded.ContentCopy
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
    isSpeaking: Boolean,
    onRegenerate: () -> Unit,
    onToggleReadAloud: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
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

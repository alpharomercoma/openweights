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
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme

/**
 * The actions under a finished reply.
 *
 * Always visible, never behind a long press. Copy is the most-used action in any chat app
 * and hiding it costs a discovery a user should not have to make; hover-to-reveal, which
 * every desktop client uses, has no mobile equivalent at all. The long-press sheet stays
 * for the rarer things.
 *
 * @param onRetry null when this is not the last reply, or while one is being generated
 *   regenerating anything other than the last turn would silently discard what came after.
 */
@Composable
fun MessageActions(
    isSpeaking: Boolean,
    onCopy: () -> Unit,
    onReadAloud: () -> Unit,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Drawn at the end of the row, after the actions, when the reply has finished. */
    measurements: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Action(Icons.Rounded.ContentCopy, "Copy reply", onCopy)
        Action(
            icon = if (isSpeaking) {
                Icons.Rounded.StopCircle
            } else {
                Icons.AutoMirrored.Rounded.VolumeUp
            },
            description = if (isSpeaking) "Stop reading aloud" else "Read aloud",
            onClick = onReadAloud,
        )
        onRetry?.let { Action(Icons.Rounded.Refresh, "Try again", it) }

        // The measurements share the action row rather than taking a line of their own.
        // They used to sit above the reply on a line that ran out of width and wrapped,
        // which on a narrow phone pushed the answer down and read as broken. Here they
        // occupy space the row already had and cannot wrap, because they are the last
        // thing in it and they ellipsize.
        measurements?.let {
            Spacer(Modifier.weight(1f))
            it()
        }
    }
}

/**
 * One action.
 *
 * The icon is drawn small and quiet while the touch target stays full size: these sit under
 * every reply, so at full weight they would compete with the answer they belong to.
 */
@Composable
private fun Action(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(TARGET.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ICON.dp),
        )
    }
}

/**
 * The minimum touch target, from the Material accessibility guidance.
 *
 * This was 40 with a comment claiming the icon's padding made up the difference. It does
 * not: padding inside the button does not grow the button.
 */
private const val TARGET = 48

private const val ICON = 18

/** Copies a reply, and says so on the versions of Android that do not say it themselves. */
class MessageClipboard(private val context: Context) {
    fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenWeights reply", text))
        // Android 13 and later show their own copy confirmation; a second toast on top of
        // the system one is noise.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun rememberMessageClipboard(): MessageClipboard {
    val context = LocalContext.current
    return remember(context) { MessageClipboard(context) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun MessageActionsPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        MessageActions(isSpeaking = false, onCopy = {}, onReadAloud = {}, onRetry = {})
    }
}

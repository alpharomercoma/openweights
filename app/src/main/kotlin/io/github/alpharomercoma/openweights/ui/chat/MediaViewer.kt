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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.model.MediaKind
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * One attachment, filling the screen over a dimmed conversation.
 *
 * A thumbnail is 64dp square and an attachment is the thing a turn is about, so until now a
 * picture somebody sent could be looked at only in the app they sent it from. This is the
 * convention every messaging app has settled on and there is no reason to differ from it:
 * tap to open, tap the background or press back to close.
 *
 * Images can be pinched. Everything else shows its kind, its name, and says plainly that
 * this app will not play it, because a viewer that pretends to open an audio file and then
 * does nothing is worse than one that says what it is.
 */
@Composable
fun MediaViewer(attachment: MessagePart.File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        // Full width, because a picture boxed inside a dialog's default insets is a
        // thumbnail with more steps.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM))
                // No ripple and no minimum touch target: this is the whole screen, and it is
                // a way out rather than a control.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (attachment.kind) {
                MediaKind.IMAGE -> ZoomableImage(attachment)
                else -> Unplayable(attachment)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(attachment: MessagePart.File) {
    var scale by remember { mutableFloatStateOf(1f) }
    AsyncImage(
        model = File(attachment.path),
        contentDescription = attachment.describe(),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    // Bounded both ways. Below one the picture floats in the middle of a
                    // black screen with no way to tell it apart from a failed load, and far
                    // above four a phone-sized photograph is a field of pixels.
                    scale = min(MAX_ZOOM, max(1f, scale * zoom))
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale),
    )
}

@Composable
private fun Unplayable(attachment: MessagePart.File) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(
            imageVector = when (attachment.kind) {
                MediaKind.AUDIO -> Icons.Rounded.AudioFile
                MediaKind.VIDEO -> Icons.Rounded.Videocam
                else -> Icons.Rounded.Description
            },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = attachment.describe(),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.sent_message_open_app_came),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = MUTED),
            textAlign = TextAlign.Center,
        )
    }
}

/** Dark enough that the conversation reads as behind glass rather than merely tinted. */
private const val SCRIM = 0.92f
private const val MUTED = 0.7f
private const val MAX_ZOOM = 4f

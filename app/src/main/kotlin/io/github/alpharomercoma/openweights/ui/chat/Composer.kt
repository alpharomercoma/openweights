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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.designsystem.theme.Motion
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius

/**
 * Where you type.
 *
 * One rounded container holding attachments, the text and the controls, rather than a
 * field with buttons scattered beside it. Every current chat app converged on this for the
 * same reason: the bar has to stay calm when empty and grow to hold thumbnails, chips and
 * a multi-line draft without the controls jumping around the screen.
 *
 * Docked to the bottom edge above the navigation bar — never floating over the last
 * message, which is the single most common mobile chat layout mistake.
 */
@Composable
@Suppress("LongParameterList")
fun Composer(
    conversationKey: Long?,
    enabled: Boolean,
    isGenerating: Boolean,
    staged: List<MessagePart.File>,
    canAttach: Boolean,
    isAttaching: Boolean,
    onAttach: () -> Unit,
    onRemoveStaged: (MessagePart.File) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onCommand: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Saveable, and keyed to the conversation. Saveable because a half-written message is
    // the one piece of state here the user cannot get back; keyed because a draft belongs
    // to the chat it was written in, and carrying it into another one would send it to a
    // model that never saw the conversation it was answering.
    var draft by rememberSaveable(conversationKey) { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }
    val commands = SlashCommand.match(draft)
    val hasSomethingToSend = draft.isNotBlank() || staged.isNotEmpty()

    // The border is the focus indicator: it is the only boundary this control has, so it
    // has to be the thing that answers when the field is live.
    val border by animateColorAsState(
        targetValue = if (isFocused) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        },
        animationSpec = Motion.quick(),
        label = "composer border",
    )

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (commands != null) {
            SlashCommandPalette(
                commands = commands,
                onSelect = { command ->
                    draft = ""
                    onCommand(command)
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(width = 1.dp, color = border, shape = RoundedCornerShape(Radius.lg)),
        ) {
            // Inside the container, not above it: an attachment is part of the message
            // being written, and showing it detached invites sending one by accident.
            AnimatedVisibility(
                visible = staged.isNotEmpty(),
                enter = fadeIn(Motion.quick()) + expandVertically(Motion.quick()),
                exit = fadeOut(Motion.instant()) + shrinkVertically(Motion.instant()),
            ) {
                StagedAttachments(
                    attachments = staged,
                    onRemove = onRemoveStaged,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                // maxLines rather than a height cap: a fixed dp ceiling is six lines at the
                // default font scale and barely three at 200%, which quietly punishes the
                // people who need the room most.
                maxLines = MAX_LINES,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .onFocusChanged { isFocused = it.isFocused }
                    .semantics { contentDescription = "Message" },
                textStyle = MaterialTheme.typography.bodyLarge
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Default,
                ),
                // A Box, so the placeholder sits behind the text rather than above it.
                // Laid out as siblings they stack, which silently doubles the height of an
                // empty composer and opens a gap nobody can explain.
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text(
                                text = "Message",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        field()
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canAttach) {
                    IconButton(onClick = onAttach, enabled = enabled && !isAttaching) {
                        if (isAttaching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Attach a file",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                SendButton(
                    isGenerating = isGenerating,
                    enabled = isGenerating || (enabled && hasSomethingToSend),
                    onClick = {
                        if (isGenerating) {
                            onStop()
                        } else if (hasSomethingToSend) {
                            onSend(draft)
                            draft = ""
                        }
                    },
                )
            }
        }
    }
}

/**
 * Send, and stop.
 *
 * One button in two states rather than two buttons, because they are never both useful and
 * a control that appears mid-conversation is a control the thumb has to hunt for. It fills
 * with the accent only when there is something to do — the rest of the time it is a hint,
 * not an invitation.
 */
@Composable
private fun SendButton(isGenerating: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = when {
            isGenerating -> MaterialTheme.colorScheme.surfaceContainerHighest
            enabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = Motion.instant(),
        label = "send container",
    )
    val content by animateColorAsState(
        targetValue = when {
            isGenerating -> MaterialTheme.colorScheme.onSurface
            enabled -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = Motion.instant(),
        label = "send content",
    )

    Box(
        modifier = Modifier.size(SEND_SIZE.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(SEND_SIZE.dp)) {
            Icon(
                imageVector = if (isGenerating) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                contentDescription = if (isGenerating) "Stop generating" else "Send message",
                tint = content,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Six lines of draft before it scrolls: past that the composer eats the conversation. */
private const val MAX_LINES = 6

/** Comfortably past the 48 dp touch minimum without dominating the bar. */
private const val SEND_SIZE = 36

@Preview(showBackground = true, backgroundColor = 0xFF0D0F11)
@Composable
private fun ComposerPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
            Composer(
                conversationKey = null,
                enabled = true,
                isGenerating = false,
                staged = emptyList(),
                canAttach = true,
                isAttaching = false,
                onAttach = {},
                onRemoveStaged = {},
                onSend = {},
                onStop = {},
                onCommand = {},
            )
        }
    }
}

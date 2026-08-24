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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.designsystem.theme.Motion
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsColors
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.model.StagedDocument

/**
 * Where you type.
 *
 * One rounded container holding attachments, the text and the controls, rather than a
 * field with buttons scattered beside it. Every current chat app converged on this for the
 * same reason: the bar has to stay calm when empty and grow to hold thumbnails, chips and
 * a multi-line draft without the controls jumping around the screen.
 *
 * Docked to the bottom edge above the navigation bar, never floating over the last
 * message, which is the single most common mobile chat layout mistake.
 */
@Composable
@Suppress("LongParameterList")
fun Composer(
    conversationKey: Long?,
    enabled: Boolean,
    isGenerating: Boolean,
    staged: List<MessagePart.File>,
    document: StagedDocument?,
    onRemoveDocument: () -> Unit,
    isAttaching: Boolean,
    canDictate: Boolean,
    isListening: Boolean,
    heard: String,
    onAttach: () -> Unit,
    onRemoveStaged: (MessagePart.File) -> Unit,
    onDictate: ((String) -> Unit) -> Unit,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    onCommand: (SlashCommand) -> Unit,
    /**
     * A message being edited, dropped into the field for the user to change.
     *
     * Editing happens here rather than in a dialog because this is where the keyboard, the
     * attachments and the send button already are, and because a question being asked again
     * is the same act as asking it the first time.
     */
    editing: String? = null,
    /** The thinking control, drawn beside Attach. Empty when the model offers no choice. */
    leading: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Saveable, and keyed to the conversation. Saveable because a half-written message is
    // the one piece of state here the user cannot get back; keyed because a draft belongs
    // to the chat it was written in, and carrying it into another one would send it to a
    // model that never saw the conversation it was answering.
    var draft by rememberSaveable(conversationKey) { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }

    // Keyed on the text itself rather than on a flag, so choosing a different message to
    // edit refills the field, and so a user who has started changing the text does not have
    // it overwritten on every recomposition.
    LaunchedEffect(editing) {
        if (editing != null) draft = editing
    }
    val commands = SlashCommand.match(draft)
    val hasSomethingToSend = draft.isNotBlank() || staged.isNotEmpty() || document != null

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

    // Dictation lives in an application-scoped object, so leaving this screen while it is
    // listening would hold the microphone and deliver a transcript into a composer that is
    // no longer on screen.
    DisposableEffect(Unit) { onDispose { if (isListening) onDictate {} } }

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

            AnimatedVisibility(
                visible = document != null,
                enter = fadeIn(Motion.quick()) + expandVertically(Motion.quick()),
                exit = fadeOut(Motion.instant()) + shrinkVertically(Motion.instant()),
            ) {
                // Held while it is visible, so the chip does not empty out mid-animation as
                // it collapses.
                val held = remember(document) { document }
                held?.let {
                    StagedDocumentChip(
                        document = it,
                        onRemove = onRemoveDocument,
                        modifier = Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp),
                    )
                }
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
                                // While listening the placeholder carries what has been
                                // heard so far, so the words appear where they will land
                                // rather than in a separate panel.
                                text = heard.ifEmpty {
                                    if (isListening) "Listening…"
                                    else stringResource(R.string.slash_command_hint)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        field()
                    }
                },
            )

            ComposerActions(
                enabled = enabled,
                isGenerating = isGenerating,
                leading = leading,
                isAttaching = isAttaching,
                canDictate = canDictate,
                isListening = isListening,
                onAttach = onAttach,
                onDictate = { onDictate { heard -> draft = draft.appended(heard) } },
                onSend = {
                    // Cleared only if it was taken. Refused messages stay in the box, which
                    // is the difference between "not yet" and "gone".
                    if (onSend(draft)) draft = ""
                },
                onStop = onStop,
                hasSomethingToSend = hasSomethingToSend,
            )
        }
    }
}

/**
 * The row beneath the text: attach on one side, dictate and send on the other.
 *
 * Its own composable because the bar's controls and the bar's text have nothing to say to
 * each other, and because keeping them together made a single function that branched on
 * every capability the model and device happen to have.
 */
@Composable
@Suppress("LongParameterList")
private fun ComposerActions(
    enabled: Boolean,
    isGenerating: Boolean,
    leading: @Composable () -> Unit,
    isAttaching: Boolean,
    canDictate: Boolean,
    isListening: Boolean,
    hasSomethingToSend: Boolean,
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Always, not only for a model that accepts media. Every model can be handed a
        // document, and a plus that comes and goes depending on which weights are loaded is
        // a plus nobody trusts.
        AttachButton(
            enabled = enabled && !isAttaching,
            isAttaching = isAttaching,
            onClick = onAttach,
        )
        // Left of the spacer, with Attach: both answer "what goes into this message",
        // while the right-hand side is for sending it.
        leading()
        Spacer(Modifier.weight(1f))
        if (canDictate) {
            DictateButton(isListening = isListening, enabled = enabled, onDictate = onDictate)
        }
        SendButton(
            isGenerating = isGenerating,
            enabled = isGenerating || (enabled && hasSomethingToSend),
            onClick = {
                if (isGenerating) {
                    onStop()
                } else if (hasSomethingToSend) {
                    onSend()
                }
            },
        )
    }
}

/** Attach, or the spinner that replaces it while a picked file is being copied in. */
@Composable
private fun AttachButton(enabled: Boolean, isAttaching: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        if (isAttaching) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.attach_file),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Send, and stop.
 *
 * One button in two states rather than two buttons, because they are never both useful and
 * a control that appears mid-conversation is a control the thumb has to hunt for. It fills
 * with the accent only when there is something to do. The rest of the time it is a hint,
 * not an invitation.
 */
@Composable
private fun SendButton(isGenerating: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = when {
            isGenerating -> MaterialTheme.colorScheme.surfaceContainerHighest
            // Lime rather than the primary role, so the one control the thumb is heading
            // for is the same colour on paper as it is on the dark canvas. `primary` is
            // ink in the light theme, because Material also paints it as text.
            enabled -> OpenWeightsColors.Lime
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = Motion.instant(),
        label = "send container",
    )
    val content by animateColorAsState(
        targetValue = when {
            isGenerating -> MaterialTheme.colorScheme.onSurface
            enabled -> OpenWeightsColors.Ink
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = Motion.instant(),
        label = "send content",
    )

    // A 36 dp circle inside a 48 dp target: the visual weight the bar wants, and the touch
    // area a thumb needs. Sizing the button itself to 36 dp, as this first did, shrinks the
    // hit region to match the paint.
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(TOUCH_TARGET.dp)) {
        Box(
            modifier = Modifier.size(SEND_SIZE.dp).clip(CircleShape).background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isGenerating) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                contentDescription = if (isGenerating) "Stop generating" else "Send message",
                tint = content,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The microphone, and the permission it needs.
 *
 * The permission is requested on the first tap and never at launch: an app that asks for
 * the microphone before you have asked it for anything has not earned the answer. Denial
 * is silent by design. The system dialog already said what happened, and a second message
 * repeating it is the app arguing with the user.
 */
@Composable
private fun DictateButton(isListening: Boolean, enabled: Boolean, onDictate: () -> Unit) {
    val context = LocalContext.current
    val microphone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onDictate() }

    // Stopping never asks for permission. Checking first meant that a permission revoked
    // mid-session turned the stop button into another request, leaving the user unable to
    // stop something the interface said was still listening.
    IconButton(
        enabled = enabled,
        onClick = {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            when {
                isListening || granted == PackageManager.PERMISSION_GRANTED -> onDictate()
                else -> microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    ) {
        Icon(
            imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
            contentDescription = if (isListening) "Stop dictation" else "Dictate a message",
            tint = if (isListening) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Appends dictated words to whatever was already typed, with a space where one is due. */
private fun String.appended(heard: String): String = if (isEmpty()) heard else "${trimEnd()} $heard"

/** Six lines of draft before it scrolls: past that the composer eats the conversation. */
private const val MAX_LINES = 6

/** The painted circle. Sized for the bar, not for the thumb. */
private const val SEND_SIZE = 36

/** The thumb's share, which Android asks to be at least this. */
private const val TOUCH_TARGET = 48

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun ComposerPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
            Composer(
                conversationKey = null,
                enabled = true,
                isGenerating = false,
                staged = emptyList(),
                document = null,
                onRemoveDocument = {},
                isAttaching = false,
                canDictate = true,
                isListening = false,
                heard = "",
                onAttach = {},
                onRemoveStaged = {},
                onDictate = {},
                onSend = { true },
                onStop = {},
                onCommand = {},
            )
        }
    }
}

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
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import io.github.alpharomercoma.openweights.core.designsystem.component.Caption
import io.github.alpharomercoma.openweights.core.designsystem.component.readableColumn
import io.github.alpharomercoma.openweights.core.designsystem.theme.Motion
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsColors
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.model.StagedDocument
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

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
// Complexity here is the layout's conditional surface — every chip, hint and control that
// comes and goes with state — and splitting it would scatter one visual container across
// functions that can only be understood together.
@Composable
@OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
@Suppress("LongParameterList", "CyclomaticComplexMethod")
fun Composer(
    conversationKey: Long?,
    /**
     * The draft persisted for this conversation, or null when none was. Seeded into the
     * field once per conversation; after that the field owns the text and this is ignored,
     * so typing is never overwritten by a stale read arriving late.
     */
    initialDraft: String? = null,
    /**
     * Where the text goes so it survives the screen. Called debounced as the user types
     * and immediately with an empty string when a send clears the field, which is also
     * what clears the stored draft.
     */
    onDraftChange: (String) -> Unit = {},
    enabled: Boolean,
    isGenerating: Boolean,
    /**
     * True while a model is coming into memory. [enabled] does not depend on this: typing,
     * attaching and dictating all work while the weights load, so a message can be finished
     * while the model is loading. [SendButton] checks this and the first-prefix preparation
     * directly, refusing the one thing that genuinely cannot happen yet; the hint says why.
     */
    isLoadingModel: Boolean = false,
    /** True while the first fresh-chat prefix is being read after the weights finish loading. */
    isPreparingFirstResponse: Boolean = false,
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
     * Pictures pasted, dropped or inserted by the keyboard into the field itself.
     *
     * The same list the attachment sheet produces, and it goes to the same place: whether
     * this model can read them is decided once, where a picked file is decided, rather than
     * twice in two voices. See [PastedMedia].
     */
    onPasteMedia: (List<Uri>) -> Unit = {},
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
    //
    // A TextFieldState rather than a String, and that is not a fashion: a picture pasted
    // into the field is only offered to the state-based field. The older one takes text
    // from the keyboard and drops everything else on the floor, which is exactly the paste
    // that used to do nothing.
    val field = rememberSaveable(conversationKey, saver = TextFieldState.Saver) {
        TextFieldState()
    }
    val draft = field.text.toString()

    // Which conversation the persisted draft has already been offered to. Saveable so a
    // rotation does not offer it again over text the user has since changed; keyed like
    // the field, so switching chats re-arms it. The sentinel is a key no conversation has.
    var seededFor by rememberSaveable(conversationKey) { mutableStateOf(UNSEEDED) }
    LaunchedEffect(conversationKey, initialDraft) {
        val key = conversationKey ?: 0L
        if (seededFor != key && initialDraft != null) {
            // Only into an empty field: a field with text in it was restored by the
            // saveable state above or already being typed into, and both outrank a read
            // from disk.
            if (field.text.isEmpty()) field.setTextAndPlaceCursorAtEnd(initialDraft)
            seededFor = key
        }
    }
    // The persist side. Debounced so a sentence costs one write, not one per character;
    // dropping the first emission keeps the value the field woke up with from being
    // written straight back where it came from.
    LaunchedEffect(conversationKey) {
        snapshotFlow { field.text.toString() }
            .drop(1)
            .debounce(DRAFT_DEBOUNCE_MS)
            .collect { onDraftChange(it) }
    }
    var isFocused by remember { mutableStateOf(false) }

    // Held rather than captured, so the listener built once still calls whatever the current
    // composition would: a receiver rebuilt on every keystroke is rebuilt while a drag is in
    // flight, and a receiver that closed over the first frame's callback would stage into a
    // conversation that has since been left.
    val context = LocalContext.current
    val currentPaste by rememberUpdatedState(onPasteMedia)
    val acceptsPaste by rememberUpdatedState(enabled)
    val mediaReceiver = remember(context) {
        PastedMedia.listener(
            // Asking a provider what it holds is a call into another app, on this thread,
            // in the middle of a paste: a provider that is gone, or that throws on a URI it
            // never meant to share, must leave the content unclaimed rather than take the
            // window down with it.
            typeOf = { uri -> runCatching { context.contentResolver.getType(uri) }.getOrNull() },
            // Nothing is staged into a composer that refuses to be typed into: while a goal
            // owns the conversation there is no message being written to attach it to.
            onMedia = { uris -> if (acceptsPaste) currentPaste(uris) },
        )
    }

    // The command an argument is being typed for, chosen from the palette rather than typed.
    // Stored by name because an enum has no built-in Saver; a command survives rotation the
    // same way the draft it is paired with does. Once chosen this way the trigger cannot be
    // corrupted by autocorrect changing a hyphen to a space mid-sentence, because it is never
    // characters in the field again — only [draft] is, and only the argument.
    var pendingCommandName by rememberSaveable(conversationKey) { mutableStateOf<String?>(null) }
    val pendingCommand = pendingCommandName?.let { name ->
        SlashCommand.entries.firstOrNull { it.name == name }
    }

    // The exact text Send was already pressed on once and refused, because it opened with a
    // slash and matched nothing. A second press on the same text is the user overruling the
    // warning rather than a stray tap, so it goes through as an ordinary message the next
    // time this is compared against an unchanged [draft].
    var unknownAttempt by rememberSaveable(conversationKey) { mutableStateOf<String?>(null) }

    // Keyed on the text itself rather than on a flag, so choosing a different message to
    // edit refills the field, and so a user who has started changing the text does not have
    // it overwritten on every recomposition.
    LaunchedEffect(editing) {
        if (editing != null) {
            field.setTextAndPlaceCursorAtEnd(editing)
            // Editing replaces a turn that already happened; parsing the reopened text as a
            // fresh command would run it instead of resending the edit.
            pendingCommandName = null
            unknownAttempt = null
        }
    }
    // Null once a command is already chosen: the field is an argument now, and a leading
    // slash typed into it is content, not a second attempt at a command.
    val commands = if (pendingCommand == null) SlashCommand.match(draft) else null
    val hasSomethingToSend = if (pendingCommand != null) {
        draft.isNotBlank()
    } else {
        draft.isNotBlank() || staged.isNotEmpty() || document != null
    }

    // Told at once, not left to the debounce above. Clearing the field only queued the
    // empty draft behind the pause, so leaving within it kept the sent question as the
    // stored draft, and the next open of the chat put a message already answered back in
    // the box. The doc on [onDraftChange] promised this and nothing kept the promise.
    fun clearSent() {
        field.clearText()
        onDraftChange("")
    }

    fun trySend() {
        // Resending, unconditionally. The reopened text goes straight to onSend, which is
        // `submit()` and already puts editing ahead of command parsing; gating it here too
        // meant an edit that happened to read like a failed command ("/tmp is full") needed
        // Send pressed twice, once to clear a warning `submit` was never going to raise.
        if (editing != null) {
            if (onSend(draft)) clearSent()
            return
        }
        val command = pendingCommand
        if (command != null) {
            if (draft.isBlank()) return
            if (onSend("${command.trigger} ${draft.trim()}")) {
                clearSent()
                pendingCommandName = null
                unknownAttempt = null
            }
            return
        }
        val parsed = SlashCommand.parse(draft)
        if (parsed is CommandParseResult.Unknown && unknownAttempt != draft) {
            // First press only warns. The text stays exactly as typed, so a second press on
            // an unchanged field is unambiguous: the warning was seen and overruled.
            unknownAttempt = draft
            return
        }
        if (onSend(draft)) {
            clearSent()
            unknownAttempt = null
        }
    }

    val unknownParse = if (editing == null && pendingCommand == null && unknownAttempt == draft) {
        SlashCommand.parse(draft) as? CommandParseResult.Unknown
    } else {
        null
    }

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
    //
    // Held the same way the paste receiver holds its callbacks. The effect runs once, and
    // the lambda it keeps closed over the first frame's `isListening`, which is false on
    // every screen anybody has ever opened: leaving mid-dictation checked that stale false
    // and left the microphone on.
    val currentListening by rememberUpdatedState(isListening)
    val currentDictate by rememberUpdatedState(onDictate)
    DisposableEffect(Unit) { onDispose { if (currentListening) currentDictate {} } }

    Column(
        modifier = modifier.readableColumn().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (commands != null) {
            SlashCommandPalette(
                commands = commands,
                enabled = enabled,
                onSelect = { command ->
                    unknownAttempt = null
                    if (command.takesArgument) {
                        // Chosen, not typed: the trigger is state from here on, not
                        // characters in the field an edit or an autocorrect could reach.
                        pendingCommandName = command.name
                        field.clearText()
                    } else {
                        pendingCommandName = null
                        field.clearText()
                        onCommand(command)
                    }
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
            // Above the field rather than beside it: the trigger is a whole word ("/deep-
            // research"), which beside a field on a phone either eats the width the argument
            // needs or wraps and pushes the row's height around while the field is focused.
            AnimatedVisibility(
                visible = pendingCommand != null,
                enter = fadeIn(Motion.quick()) + expandVertically(Motion.quick()),
                exit = fadeOut(Motion.instant()) + shrinkVertically(Motion.instant()),
            ) {
                pendingCommand?.let { command ->
                    CommandChip(
                        command = command,
                        onRemove = {
                            // The argument is kept. Removing the command is "I did not mean
                            // that command", not "I take back the sentence I wrote for it".
                            pendingCommandName = null
                        },
                        modifier = Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = unknownParse != null,
                enter = fadeIn(Motion.quick()) + expandVertically(Motion.quick()),
                exit = fadeOut(Motion.instant()) + shrinkVertically(Motion.instant()),
            ) {
                unknownParse?.let { unknown ->
                    UnknownCommandNotice(
                        token = unknown.token,
                        suggestion = unknown.suggestions.firstOrNull(),
                        enabled = enabled,
                        onUseSuggestion = { suggestion ->
                            unknownAttempt = null
                            if (suggestion.takesArgument) {
                                pendingCommandName = suggestion.name
                                // The whole near-miss trigger comes off, not just its first
                                // word: "/deep research what changed" against a suggestion
                                // of "/deep-research" leaves "what changed", not "research
                                // what changed" with half the trigger still attached to it.
                                field.setTextAndPlaceCursorAtEnd(
                                    suggestion.argumentAfterNearMiss(draft),
                                )
                            } else {
                                // A no-argument command runs the same way choosing it from
                                // the palette does. Staging it as pending instead built a
                                // trigger-plus-argument string submit() does not recognise
                                // for a command that never takes one, and the correction
                                // reached the model as prose.
                                pendingCommandName = null
                                field.clearText()
                                onCommand(suggestion)
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp),
                    )
                }
            }

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
                state = field,
                // False while an unattended goal owns the conversation, or while the field
                // has nothing to answer to (no model installed at all) — not while a model
                // is merely loading, where a draft written now is still worth having typed.
                enabled = enabled,
                // A line ceiling rather than a height in dp: a fixed dp ceiling is six lines
                // at the default font scale and barely three at 200%, which quietly punishes
                // the people who need the room most.
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = MAX_LINES),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .onFocusChanged { isFocused = it.isFocused }
                    // Before the semantics, and on the field rather than on the container:
                    // this is what makes a pasted, dropped or keyboard-inserted picture
                    // reach the message instead of being dropped on the floor.
                    .contentReceiver(mediaReceiver)
                    .semantics { contentDescription = "Message" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                // A Box, so the placeholder sits behind the text rather than above it.
                // Laid out as siblings they stack, which silently doubles the height of an
                // empty composer and opens a gap nobody can explain.
                decorator = TextFieldDecorator { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text(
                                // While listening the placeholder carries what has been
                                // heard so far, so the words appear where they will land
                                // rather than in a separate panel.
                                text = heard.ifEmpty {
                                    when {
                                        isListening -> "Listening…"
                                        // The command's own description, already written to
                                        // say what it does; a second, command-specific
                                        // sentence here would be one more string to keep in
                                        // step with it for no reader who cannot already see
                                        // the chip above.
                                        pendingCommand != null -> pendingCommand.description
                                        else -> stringResource(R.string.slash_command_hint)
                                    }
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
                isLoadingModel = isLoadingModel,
                isPreparingFirstResponse = isPreparingFirstResponse,
                leading = leading,
                isAttaching = isAttaching,
                canDictate = canDictate,
                isListening = isListening,
                onAttach = onAttach,
                onDictate = {
                    onDictate { heard ->
                        field.setTextAndPlaceCursorAtEnd(field.text.toString().appended(heard))
                    }
                },
                onSend = ::trySend,
                onStop = onStop,
                hasSomethingToSend = hasSomethingToSend,
            )
        }
    }
}

/**
 * The command an argument is being written for, and the way to change your mind.
 *
 * A chip rather than the trigger sitting in the field as more characters, which is what let
 * autocorrect or a stray edit turn "/deep-research" into something that no longer matches
 * anything. Removing it keeps the argument: it says "not that command", not "not that
 * sentence".
 */
@Composable
private fun CommandChip(
    command: SlashCommand,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = command.trigger,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.remove_command, command.trigger),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What Send meant when the text it was pressed on opened with a slash and matched nothing.
 *
 * Said once, so the first press is a warning rather than a silent trip to the model: typing
 * "/deep research" with a space where the trigger has a hyphen used to answer the question
 * as prose with nothing on screen to say a command had even been attempted. A second press
 * on the same text is the user overruling this, which [Composer] reads by comparing the
 * field against what was warned about rather than by a button here.
 */
@Composable
private fun UnknownCommandNotice(
    token: String,
    suggestion: SlashCommand?,
    /**
     * Whether accepting the suggestion would do anything.
     *
     * A no-argument suggestion now runs immediately rather than waiting for its own Send,
     * which means this button is a second way into the view model beside Send and the
     * palette — both of which already stop while the composer is disabled. A model loading
     * or a reply already running is exactly when a stray tap should not also start `/retry`.
     */
    enabled: Boolean,
    onUseSuggestion: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.unknown_command, token),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (suggestion != null) {
            TextButton(
                onClick = { onUseSuggestion(suggestion) },
                enabled = enabled,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(R.string.unknown_command_suggestion, suggestion.trigger))
            }
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
    isLoadingModel: Boolean,
    isPreparingFirstResponse: Boolean,
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
        if (isLoadingModel || isPreparingFirstResponse) {
            LoadingModelHint(
                text = stringResource(
                    if (isPreparingFirstResponse) {
                        R.string.preparing_first_reply
                    } else {
                        R.string.loading_model
                    },
                ),
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (canDictate) {
            DictateButton(isListening = isListening, enabled = enabled, onDictate = onDictate)
        }
        SendButton(
            isGenerating = isGenerating,
            // The one control that still waits on the model itself: everything else in this
            // bar works on a loading model, but there is nothing to send it to yet. Nor
            // while the first prompt is being prepared or a file is still being copied in:
            // sending then would either make the first turn cold or omit the attachment.
            enabled = isGenerating ||
                (enabled &&
                    !isLoadingModel &&
                    !isPreparingFirstResponse &&
                    !isAttaching &&
                    hasSomethingToSend),
            onClick = {
                if (isGenerating) {
                    onStop()
                } else if (hasSomethingToSend && !isAttaching) {
                    onSend()
                }
            },
        )
    }
}

/**
 * What fills the space between Attach and Send while a model is coming into memory.
 *
 * The composer is disabled either way, but "disabled" alone reads as broken. This is the
 * same fact [ChatScreen]'s full-screen [LoadingTheModel] shows for an empty chat, said in
 * the one place still on screen once there's a conversation to look at instead: the bar the
 * thumb is already resting on.
 */
@Composable
private fun LoadingModelHint(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Caption(text = text, maxLines = 1)
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

/**
 * Eight lines of draft before it scrolls: past that the composer eats the conversation.
 *
 * Was six. This app's users paste prompts, not sentences — a multi-paragraph instruction
 * is the normal case for a developer playground — and at six lines the window into a
 * pasted prompt was small enough that checking what you were about to send meant
 * scrolling inside the field. Eight is about a third of a phone screen with the keyboard
 * up, which still leaves the last exchange visible above it; past that the field scrolls,
 * so nothing is ever lost, only out of frame.
 */
private const val MAX_LINES = 8

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

/** No conversation has this key, so a fresh composer always accepts its first seed. */
private const val UNSEEDED = Long.MIN_VALUE

/** One write per pause in typing, not one per character. */
private const val DRAFT_DEBOUNCE_MS = 400L

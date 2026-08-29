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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius

/**
 * The four things you can do to a conversation without opening it.
 *
 * One parameter rather than four, for the reason [ChatDestinations] gives: `ChatScreen`
 * already takes more arguments than anything else here. Delete used to be the only one,
 * and it was a bin icon sitting in every row of the drawer — permanently visible, one
 * mis-tap from wiping a conversation that has no undo, and taking width from the title in
 * exchange. All four now live behind one overflow button in the same space.
 */
data class ConversationActions(
    val onRename: (Long, String) -> Unit = { _, _ -> },
    val onPin: (Long, Boolean) -> Unit = { _, _ -> },
    val onArchive: (Long, Boolean) -> Unit = { _, _ -> },
    val onDelete: (Long) -> Unit = {},
)

/**
 * Pin, rename, archive and delete, for one conversation.
 *
 * A bottom sheet rather than a dropdown anchored to the row, and the reason is the hand
 * holding the phone. A `DropdownMenu` opens where its anchor is, so the menu for the first
 * chat in a long list opens at the top of a six-inch screen, which is the one part of it a
 * thumb cannot reach; a sheet is always at the bottom. It is also the shape this app
 * already uses for "actions on one thing" — see [MessageActionsSheet], which the row's
 * long-press mirrors — and it has room for a label in Arabic or Japanese, which a menu
 * item 180dp wide does not.
 *
 * Pin and archive are toggles rather than pairs of rows: the row says what tapping it
 * does, so a pinned chat offers Unpin and nothing else. Pin is absent altogether while a
 * chat is archived, for the reason given where that is decided.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationActionsSheet(
    conversation: ConversationSummary,
    actions: ConversationActions,
    onRename: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            // Which conversation this is about. A sheet covering the list has taken the row
            // that was under the thumb out of view, and four unlabelled verbs over a
            // half-hidden list is how the wrong chat gets deleted.
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            // Not offered while a conversation is filed away. Pinning says where a chat
            // sits in the list, archiving says whether it is in the list at all, so a pin
            // applied to an archived chat is a control that visibly does nothing — the row
            // stays exactly where it is, in the archive. The pin it already had is kept and
            // means something again the moment it comes back.
            if (!conversation.isArchived) {
                ActionRow(
                    icon = Icons.Rounded.PushPin,
                    label = stringResource(
                        if (conversation.isPinned) R.string.unpin_chat else R.string.pin_chat,
                    ),
                    onClick = {
                        actions.onPin(conversation.id, !conversation.isPinned)
                        onDismiss()
                    },
                )
            }
            ActionRow(
                icon = Icons.Rounded.DriveFileRenameOutline,
                label = stringResource(R.string.rename_chat),
                // Raised by the screen rather than from in here: a dialog opened by a sheet
                // that is closing at the same moment races the sheet's own animation, and
                // Material dismisses the dialog with it often enough to be a bug report.
                onClick = onRename,
            )
            ActionRow(
                icon = if (conversation.isArchived) {
                    Icons.Rounded.Unarchive
                } else {
                    Icons.Rounded.Archive
                },
                label = stringResource(
                    if (conversation.isArchived) {
                        R.string.unarchive_chat
                    } else {
                        R.string.archive_chat
                    },
                ),
                onClick = {
                    actions.onArchive(conversation.id, !conversation.isArchived)
                    onDismiss()
                },
            )
            // Red, and last, and it asks. It is the only irreversible thing in the sheet,
            // and now that Archive is directly above it there is somewhere else to send
            // somebody who only wanted the chat out of the way.
            ActionRow(
                icon = Icons.Rounded.Delete,
                label = stringResource(R.string.delete_chat),
                tint = MaterialTheme.colorScheme.error,
                onClick = onConfirmDelete,
            )
        }
    }
}

/**
 * The name field, with the current name selected.
 *
 * Selected rather than merely placed after: renaming "Write a Kotlin data class" to
 * "Taxes" is retyping it, and a caret at the end would make that thirty backspaces on a
 * phone keyboard. Editing a word still works — the first arrow key or tap collapses it.
 */
@Composable
fun RenameConversationDialog(
    conversation: ConversationSummary,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(conversation.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(conversation.title, selection = TextRange(0, conversation.title.length)),
        )
    }
    val focus = remember { FocusRequester() }
    // The dialog exists to type in, so the keyboard should not need a second tap to raise.
    LaunchedEffect(Unit) { focus.requestFocus() }

    val canSave = name.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_chat)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.chat_name)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (canSave) onRename(name.text) },
                ),
                shape = RoundedCornerShape(Radius.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .testTag(RENAME_FIELD),
            )
        },
        confirmButton = {
            // Disabled rather than saving nothing. A row with an empty title is
            // indistinguishable from the one above it and there is no undo.
            TextButton(onClick = { onRename(name.text) }, enabled = canSave) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The one confirmation in the sheet, because deleting a conversation cannot be undone. */
@Composable
fun DeleteConversationDialog(
    conversation: ConversationSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_chat_title)) },
        text = { Text(stringResource(R.string.delete_chat_message, conversation.title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    /** Set only by Delete, which colours both its icon and its word. */
    tint: Color? = null,
) {
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
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint ?: Color.Unspecified,
        )
    }
}

/** Named so an instrumented test can type into the field without reading its label. */
const val RENAME_FIELD = "rename-conversation-field"

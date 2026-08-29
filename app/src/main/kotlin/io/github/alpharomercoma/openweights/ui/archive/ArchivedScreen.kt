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

package io.github.alpharomercoma.openweights.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.data.groupByDay
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.ui.chat.ConversationActions
import io.github.alpharomercoma.openweights.ui.chat.ConversationActionsSheet
import io.github.alpharomercoma.openweights.ui.chat.ConversationRow
import io.github.alpharomercoma.openweights.ui.chat.DeleteConversationDialog
import io.github.alpharomercoma.openweights.ui.chat.RenameConversationDialog
import io.github.alpharomercoma.openweights.ui.chat.asSummary
import io.github.alpharomercoma.openweights.ui.chat.highlighting
import java.time.Instant
import java.time.ZoneId

/**
 * Conversations that have been filed away, on a screen of their own.
 *
 * A destination rather than a section at the end of the drawer's list, and that was a
 * correction: a section at the end of a list of every conversation you have ever had is
 * only reachable by scrolling past every conversation you have ever had. It is the way
 * ChatGPT does it too, where the archive lives outside the sidebar entirely — though this
 * is one tap from the drawer rather than three through Settings, because filing a chat is a
 * thing people do with chats, not a data-control preference.
 *
 * Grouped by when each conversation was last talked in, not by when it was filed. The
 * question somebody brings here is "when did I have that conversation", which is the
 * chronology the drawer already sorts by; when it was archived is a fact about the filing
 * and nobody remembers it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
fun ArchivedScreen(
    state: ArchiveUiState,
    onSearch: (String) -> Unit,
    onOpen: (Long) -> Unit,
    actions: ConversationActions,
    onBack: () -> Unit,
    nowMillis: Long = System.currentTimeMillis(),
    modifier: Modifier = Modifier,
) {
    var menuFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.archived),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Only once there is an archive worth searching. A search box over an empty
            // screen is a control that cannot succeed.
            if (state.conversations.isNotEmpty() || state.isSearching) {
                ArchiveSearchField(value = state.query, onValueChange = onSearch)
            }
            ArchiveBody(
                state = state,
                nowMillis = nowMillis,
                onOpen = onOpen,
                onMenu = { menuFor = it },
            )
        }
    }

    // Looked up in both lists, because a row can be on screen from either. The frozen
    // search result is the fallback: the live list is the truth for a title that was just
    // changed, but a row can be in the results and, a moment after being unarchived, gone
    // from the live list while the sheet is still open on it.
    val target = state.conversations.firstOrNull { it.id == menuFor }
        ?: state.results.firstOrNull { it.id == menuFor }?.asSummary()

    target?.takeIf { menuFor != null }?.let { conversation ->
        ConversationActionsSheet(
            conversation = conversation,
            actions = actions,
            onRename = {
                renaming = conversation.id
                menuFor = null
            },
            onConfirmDelete = {
                deleting = conversation.id
                menuFor = null
            },
            onDismiss = { menuFor = null },
        )
    }
    renaming?.let { id ->
        (state.conversations.firstOrNull { it.id == id })?.let { conversation ->
            RenameConversationDialog(
                conversation = conversation,
                onRename = {
                    actions.onRename(id, it)
                    renaming = null
                },
                onDismiss = { renaming = null },
            )
        }
    }
    deleting?.let { id ->
        (state.conversations.firstOrNull { it.id == id })?.let { conversation ->
            DeleteConversationDialog(
                conversation = conversation,
                onConfirm = {
                    actions.onDelete(id)
                    deleting = null
                },
                onDismiss = { deleting = null },
            )
        }
    }
}

/**
 * The rows, the search results, or the sentence that says there are neither.
 *
 * Split in two below rather than branched through, because they are two different screens
 * that happen to share a container: one is the archive, the other is an answer to a
 * question about it.
 */
@Composable
private fun ArchiveBody(
    state: ArchiveUiState,
    nowMillis: Long,
    onOpen: (Long) -> Unit,
    onMenu: (Long) -> Unit,
) {
    if (state.isSearching) {
        ArchiveResults(state = state, nowMillis = nowMillis, onOpen = onOpen, onMenu = onMenu)
    } else {
        ArchiveList(state = state, nowMillis = nowMillis, onOpen = onOpen, onMenu = onMenu)
    }
}

/**
 * What a search of the archive found.
 *
 * Nothing at all until the read has answered for this exact term: "no archived chat
 * mentions that" while the answer is still coming is a wrong answer shown confidently, and
 * on a fast phone it appeared and vanished on every first keystroke.
 */
@Composable
private fun ArchiveResults(
    state: ArchiveUiState,
    nowMillis: Long,
    onOpen: (Long) -> Unit,
    onMenu: (Long) -> Unit,
) {
    if (!state.hasAnswer) return
    // One read's answer, frozen, filtered against the archive as it is now. A result is a
    // row of the archive by definition, so anything no longer in the archive has since
    // been deleted or taken back out — and leaving it on screen would leave something
    // tappable that opens nothing, or a chat offering to be unarchived twice. Nothing
    // re-runs the query, and re-running it on every edit would empty the list for a
    // debounce and refill it.
    val live = state.results.filter { match -> state.conversations.any { it.id == match.id } }
    if (live.isEmpty()) {
        Explanation(stringResource(R.string.no_archived_match))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(live, key = { it.id }) { match ->
            // The live row where there is one, the frozen match otherwise — the same rule
            // the drawer's results follow, and for the same reason: renaming a chat from
            // its search result must not leave the row it was done from showing the old
            // name, because nothing re-runs the query.
            ConversationRow(
                conversation = state.conversations.firstOrNull { it.id == match.id }
                    ?: match.asSummary(),
                isActive = false,
                nowMillis = nowMillis,
                onOpen = { onOpen(match.id) },
                onMenu = { onMenu(match.id) },
                snippet = match.snippet?.highlighting(state.query),
            )
        }
    }
}

/**
 * The archive itself, grouped by when each conversation was last talked in.
 *
 * Not by when it was filed. The question somebody brings here is "when did I have that
 * conversation", which is the chronology the drawer already sorts by; when it was archived
 * is a fact about the filing, and nobody remembers it.
 */
@Composable
private fun ArchiveList(
    state: ArchiveUiState,
    nowMillis: Long,
    onOpen: (Long) -> Unit,
    onMenu: (Long) -> Unit,
) {
    // Nothing is claimed before the rows have been read. See ArchiveUiState.loaded.
    if (!state.loaded) return
    val explanation = state.error
        ?: stringResource(R.string.nothing_archived).takeIf { state.conversations.isEmpty() }
    if (explanation != null) {
        // Reached by unarchiving the last one, and the screen stays put rather than popping
        // itself: an interface that collapses out from under the tap that emptied it reads
        // as a crash, and the way back is the arrow that has been there all along.
        Explanation(explanation)
        return
    }

    val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        state.conversations.groupByDay(today) { it.updatedAt }.forEach { group ->
            item(key = "header-${group.label}") {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            items(group.items, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    isActive = false,
                    nowMillis = nowMillis,
                    onOpen = { onOpen(conversation.id) },
                    onMenu = { onMenu(conversation.id) },
                )
            }
        }
    }
}

@Composable
private fun Explanation(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

/**
 * Searching the archive, by title and by anything said inside it.
 *
 * Full text, not a filter over the titles already on screen. People remember a phrase
 * somebody said, not the title generated from their own first message, and having to leave
 * the archive to use the drawer's search would be this screen admitting it cannot answer
 * the question it exists for.
 */
@Composable
private fun ArchiveSearchField(value: String, onValueChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        placeholder = { Text(stringResource(R.string.search_archived)) },
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.clear_search),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = RoundedCornerShape(Radius.md),
    )
}

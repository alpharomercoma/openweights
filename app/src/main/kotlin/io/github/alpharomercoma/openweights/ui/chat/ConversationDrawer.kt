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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.data.db.ConversationMatch
import io.github.alpharomercoma.openweights.core.data.groupByDay
import io.github.alpharomercoma.openweights.core.designsystem.component.AccentButton
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Past conversations and the way to everything that is not a conversation.
 *
 * The drawer is where every chat app puts history, so it is where people look, and now that
 * the app has no bottom bar it is also the only way to Tools, Usage and Settings. Those live
 * in a footer pinned to the bottom rather than at the end of the list, because a list of
 * forty chats would otherwise bury them, and "where are the settings" is not a question a
 * person should have to scroll to answer.
 *
 * Titles come from the first thing you said, which is what makes the list scannable. The
 * alternative is a column of identical dates.
 */
@Composable
@Suppress("LongParameterList")
fun ConversationDrawer(
    conversations: List<ConversationSummary>,
    activeId: Long?,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNewChat: () -> Unit,
    nowMillis: Long,
    destinations: ChatDestinations = ChatDestinations(),
    search: String = "",
    results: List<ConversationMatch> = emptyList(),
    hasSearchAnswer: Boolean = false,
    onSearch: (String) -> Unit = {},
) {
    // Narrower than Material's default, and this is not a taste decision.
    //
    // `ModalDrawerSheet` is 360dp wide at most, and a great many phones are exactly 360dp
    // wide: 1080 pixels at 480dpi is the most common handset there is. On one of those the
    // sheet covers the screen edge to edge, which leaves no scrim to tap, and tapping
    // outside a drawer to shut it is how everybody shuts a drawer. There was nothing
    // outside it. The fraction keeps a strip of the conversation visible at any width, and
    // the cap keeps a tablet from handing the drawer a third of a large screen.
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(SHEET_FRACTION).widthIn(max = SHEET_MAX),
    ) {
        // The one filled control in the drawer, so the eye lands on it first. Lime carries
        // ink, never white, which is the rule the whole palette is built on.
        AccentButton(
            onClick = onNewChat,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.new_chat), modifier = Modifier.padding(start = 8.dp))
        }

        // Under New chat rather than above it. Starting one is the thing people come here to
        // do; finding an old one is the thing they come here to do when they cannot remember
        // its name, which is exactly when a box that says so is worth the row it costs.
        SearchField(value = search, onValueChange = onSearch)

        // Weighted, so the footer stays pinned to the bottom whether there are no chats or
        // forty. The empty case used to return early from the sheet, which after the footer
        // arrived would have left somebody with no conversations no route to Settings at all.
        Box(modifier = Modifier.weight(1f)) {
            if (search.isNotBlank()) {
                SearchResults(
                    results = results,
                    term = search,
                    hasAnswer = hasSearchAnswer,
                    activeId = activeId,
                    onOpen = onOpen,
                    onDelete = onDelete,
                    nowMillis = nowMillis,
                )
            } else if (conversations.isEmpty()) {
                Text(
                    text = stringResource(R.string.nothing_here_yet_whatever_ask),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                ConversationList(
                    conversations = conversations,
                    activeId = activeId,
                    onOpen = onOpen,
                    onDelete = onDelete,
                    nowMillis = nowMillis,
                )
            }
        }

        DrawerFooter(destinations)
    }
}

/**
 * Tools, Usage and Settings, which have nowhere else to be.
 *
 * A hairline above them and nothing else: they are a different kind of thing from a
 * conversation and the rule is enough to say so without a heading that would only repeat
 * what the three labels already say.
 */
@Composable
private fun DrawerFooter(destinations: ChatDestinations) {
    HorizontalDivider(
        thickness = Dp.Hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        DrawerDestination(
            stringResource(R.string.gallery),
            Icons.Rounded.Image,
            destinations.onOpenGallery,
        )
        DrawerDestination("Tools", Icons.Rounded.Build, destinations.onOpenTools)
        DrawerDestination("Usage", Icons.Rounded.BarChart, destinations.onOpenUsage)
        DrawerDestination("Watching", Icons.Rounded.Visibility, destinations.onOpenWatches)
        DrawerDestination("Settings", Icons.Rounded.Settings, destinations.onOpenSettings)
    }
}

@Composable
private fun DrawerDestination(label: String, icon: ImageVector, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = false,
        onClick = onClick,
        shape = RoundedCornerShape(Radius.pill),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun ConversationList(
    conversations: List<ConversationSummary>,
    activeId: Long?,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    nowMillis: Long,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Grouped by the day each chat was last touched, the way every assistant
        // does it, because a flat list of forty titles is a list nobody scans.
        val today = Instant.ofEpochMilli(nowMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

        conversations.groupByDay(today) { it.updatedAt }.forEach { group ->
            item(key = "header-${group.label}") {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            items(group.items, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    isActive = conversation.id == activeId,
                    nowMillis = nowMillis,
                    onOpen = { onOpen(conversation.id) },
                    onDelete = { onDelete(conversation.id) },
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    isActive: Boolean,
    nowMillis: Long,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    snippet: AnnotatedString? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            )
            .combinedClickable(onClick = onOpen)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Why this row is in the list, when it is here because of something said in it
            // rather than because of its name. Two lines, because one is often half a word.
            snippet?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // One line, because a model name is long and this is a narrow column. Metric
            // wraps by default, so on a small screen "LFM2.5-2.6B-QAD-Q4_0" took a second
            // line, broke mid-token, and made every row in the history a different height.
            Metric(
                listOfNotNull(
                    conversation.updatedAt.asRelativeTime(nowMillis),
                    conversation.modelName,
                ).joinToString(" · "),
                maxLines = 1,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(
                    R.string.delete_conversation_named,
                    conversation.title,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The snippet with the word that matched picked out of it.
 *
 * Two lines of prose with the reason for the row somewhere inside them is a row the eye has
 * to read rather than scan. Bold rather than a colour: the palette keeps lime for actions,
 * and a coloured word in a preview reads as a link to somewhere.
 */
private fun String.highlighting(term: String): AnnotatedString {
    val at = indexOf(term, ignoreCase = true)
    if (term.isBlank() || at < 0) return AnnotatedString(this)
    return buildAnnotatedString {
        append(this@highlighting.take(at))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(this@highlighting.substring(at, at + term.length))
        }
        append(this@highlighting.substring(at + term.length))
    }
}

/**
 * The box that searches every conversation.
 *
 * One field, no button and no separate screen. The drawer is already the list of chats, so
 * searching it in place is the shortest route between "I know I asked this once" and the
 * answer, and it leaves the list exactly where it was when the box is cleared.
 */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        placeholder = { Text(stringResource(R.string.search_chats)) },
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            // Only once there is something to clear. A permanent × on an empty field is a
            // control that does nothing, next to a placeholder that says what the field is.
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
        // Search rather than a newline, and the keyboard goes when it is pressed: the results
        // are directly under the box, and a keyboard covering them is the one thing a search
        // on a phone must not do.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = RoundedCornerShape(Radius.md),
    )
}

/**
 * What a search found, newest first, each with the line that matched.
 *
 * Not grouped by day. A day heading answers "when was this", which is the question the
 * unsearched list is for; a result list answers "which one said this", and the snippet is
 * what answers it. Sorting stays by recency because two chats about the same thing are
 * usually distinguished by which was last.
 */
@Composable
@Suppress("LongParameterList")
private fun SearchResults(
    results: List<ConversationMatch>,
    term: String,
    hasAnswer: Boolean,
    activeId: Long?,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    nowMillis: Long,
) {
    // Nothing at all until the read has answered for this exact term. Saying "no chat
    // mentions that" while the answer is still being fetched is a wrong answer shown
    // confidently, and on a fast phone it appeared and vanished on every first keystroke.
    if (!hasAnswer) return
    if (results.isEmpty()) {
        Text(
            text = stringResource(R.string.chat_mentions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(results, key = { it.id }) { match ->
            ConversationRow(
                conversation = ConversationSummary(
                    id = match.id,
                    title = match.title,
                    modelName = match.modelName,
                    updatedAt = match.updatedAt,
                ),
                isActive = match.id == activeId,
                nowMillis = nowMillis,
                onOpen = { onOpen(match.id) },
                onDelete = { onDelete(match.id) },
                snippet = match.snippet?.highlighting(term),
            )
        }
    }
}

/**
 * "3h ago" rather than a timestamp.
 *
 * On a list you scan, elapsed time answers "is this the one I was just in?" faster than a
 * clock reading does.
 */
internal fun Long.asRelativeTime(nowMillis: Long): String {
    val elapsed = (nowMillis - this).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)

    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "${minutes}m ago"
        hours < HOURS_PER_DAY -> "${hours}h ago"
        days < DAYS_PER_WEEK -> "${days}d ago"
        else -> "${days / DAYS_PER_WEEK}w ago"
    }
}

/** Enough of the conversation left showing to be worth tapping, at any screen width. */
private const val SHEET_FRACTION = 0.85f

/** Material's own maximum, which only binds on a screen wider than about 420dp. */
private val SHEET_MAX = 360.dp

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val DAYS_PER_WEEK = 7

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun ConversationDrawerPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        ConversationDrawer(
            conversations = listOf(
                ConversationSummary(1, "What is a KV cache?", "LFM2.5-2.6B-Q4_K_M", 0),
                ConversationSummary(2, "Write a Kotlin data class", "LFM2.5-2.6B-Q4_K_M", 0),
            ),
            activeId = 1,
            onOpen = {},
            onDelete = {},
            onNewChat = {},
            nowMillis = TimeUnit.HOURS.toMillis(3),
        )
    }
}

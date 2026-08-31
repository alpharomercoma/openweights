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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import io.github.alpharomercoma.openweights.core.data.DayGroup
import io.github.alpharomercoma.openweights.core.data.db.ConversationMatch
import io.github.alpharomercoma.openweights.core.data.groupByDay
import io.github.alpharomercoma.openweights.core.designsystem.component.AccentButton
import io.github.alpharomercoma.openweights.core.designsystem.component.Mark
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import java.time.Instant
import java.time.LocalDate
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
    onNewChat: () -> Unit,
    nowMillis: Long,
    /** Pin, rename, archive and delete, all behind one row's overflow button. */
    actions: ConversationActions = ConversationActions(),
    /** How many conversations have been filed away. Zero hides the way in entirely. */
    archivedCount: Int = 0,
    onOpenArchive: () -> Unit = {},
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
    // Which conversation the overflow is open for, and what it is being asked of it.
    //
    // Ids rather than captured rows, and looked up again on every composition. The list is
    // a flow: a copy taken when the sheet opened would go stale the moment anything wrote
    // to that row — pinning it from here is exactly such a write, and the sheet would then
    // still be offering "Pin". A row that goes away entirely closes the sheet with it,
    // which is the right answer when the conversation was deleted from somewhere else.
    // Whether the search box has been asked for. Held here rather than derived from the
    // term alone, because an empty term is the state a search *starts* in: derived, the box
    // would vanish the moment somebody cleared it to type something else.
    var searching by rememberSaveable { mutableStateOf(false) }

    var menuFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        // Order is load-bearing and was the tablet bug: with the fraction first, its
        // min-width constraint (0.85 of the whole screen) overrode the cap that follows,
        // and a 1280dp tablet drew a 1088dp drawer. The cap has to constrain first so the
        // fraction is taken of the capped width, not of the screen.
        modifier = Modifier.widthIn(max = SHEET_MAX).fillMaxWidth(SHEET_FRACTION),
    ) {
        // A term that outlived the box — restored with the drawer, or left by a caller —
        // still gets one, or the results below would have nothing above them explaining
        // what they are results of.
        val showSearch = searching || search.isNotBlank()

        // The drawer says whose drawer it is, which every other surface in this app already
        // did and this one never has. It opened on a button, which is the shape of a dialog
        // rather than of a place, and it is the reason this sheet read as anonymous beside
        // the ones it was compared against.
        //
        // The name is set in the same style as the model name in the top bar, which is the
        // most-read title in the app: one Display face, one weight, no second treatment
        // invented for a single row. The mark beside it is the launcher tile and the empty
        // state's mark, drawn from the same five ratios, so this is the third place the same
        // identity appears rather than a fourth thing to recognise.
        DrawerBrand(
            searching = showSearch,
            onToggleSearch = {
                if (showSearch) {
                    // Clearing is part of closing. Leaving the term behind would put the
                    // list back while a search was still live in state, and the next open
                    // would show results for something typed days ago.
                    onSearch("")
                    searching = false
                } else {
                    searching = true
                }
            },
        )

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

        // Under New chat, and still under it now that it is summoned rather than permanent.
        // Starting a chat is the thing people come here to do; finding an old one is what
        // they come here for when they cannot remember its name. The order also keeps the
        // box directly above its own results, which is what stops the keyboard covering
        // them — put next to the button that opens it, in the header, New chat would sit
        // between the two.
        //
        // A box rather than the permanent field it replaced. Four rows of chrome stood
        // between the top of the drawer and the first conversation, which on a 360dp phone
        // is most of the space a phone has for history; the field was the row worth
        // spending, because the search behind it is the one thing here that is genuinely
        // one tap away either way.
        if (showSearch) {
            SearchField(value = search, onValueChange = onSearch)
        }

        // The way into the archive, and it sits here for a reason it took a rewrite to get
        // right. It began as a section at the end of the list, which meant reaching it took
        // scrolling past every conversation ever had — a fixed thing at the end of an
        // unbounded list. It is now outside the scrolling list altogether, so where the
        // history happens to be scrolled to cannot hide it.
        //
        // Here rather than in the footer beside Tools and Settings, though that is also
        // always visible: an archived conversation is still history, and belongs with the
        // search box and the list rather than beside the app's settings. A fifth footer
        // item that came and went would also move four controls people already know the
        // position of, where a row here only shifts the list beneath it.
        //
        // Only when there is an archive. Somebody who never files anything gets no dead
        // control, and the row appears at the moment it first means something — which is
        // also the answer to "where did that chat go", since it is in view when it happens.
        if (archivedCount > 0 && search.isBlank()) {
            ArchiveEntry(count = archivedCount, onClick = onOpenArchive)
        }

        // Weighted, so the footer stays pinned to the bottom whether there are no chats or
        // forty. The empty case used to return early from the sheet, which after the footer
        // arrived would have left somebody with no conversations no route to Settings at all.
        Box(modifier = Modifier.weight(1f)) {
            if (search.isNotBlank()) {
                SearchResults(
                    results = results,
                    live = conversations,
                    term = search,
                    hasAnswer = hasSearchAnswer,
                    activeId = activeId,
                    onOpen = onOpen,
                    onMenu = { menuFor = it },
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
                    onMenu = { menuFor = it },
                    nowMillis = nowMillis,
                )
            }

            // The list runs under the footer, and without this it is cut mid-row by the
            // hairline above Tools: half a title and half its timestamp, sitting there
            // looking like a rendering fault rather than like more to scroll. Every drawer
            // worth copying fades here instead.
            //
            // Painted rather than clipped, and it costs nothing when there is nothing to
            // hide: a gradient that ends in the sheet's own colour is invisible over the
            // sheet's own colour, so a drawer with three chats in it shows no band. It
            // takes no pointer input either, so the row beneath stays tappable through it.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(FADE)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ),
                    ),
            )
        }

        DrawerFooter(destinations)
    }

    // Looked up in the results as well as in the list, and that is not belt and braces.
    // The list no longer holds archived conversations at all — the query behind it excludes
    // them — but a search finds them and marks them, so the row under the finger can be one
    // the list has never heard of. Without the fallback its overflow button opened nothing.
    val onScreen = { id: Long? ->
        conversations.firstOrNull { it.id == id }
            ?: results.firstOrNull { it.id == id }?.asSummary()
    }

    onScreen(menuFor)?.let { target ->
        ConversationActionsSheet(
            conversation = target,
            actions = actions,
            onRename = {
                renaming = target.id
                menuFor = null
            },
            onConfirmDelete = {
                deleting = target.id
                menuFor = null
            },
            onDismiss = { menuFor = null },
        )
    }
    onScreen(renaming)?.let { target ->
        RenameConversationDialog(
            conversation = target,
            onRename = {
                actions.onRename(target.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    onScreen(deleting)?.let { target ->
        DeleteConversationDialog(
            conversation = target,
            onConfirm = {
                actions.onDelete(target.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * The drawer's list, in the three parts it is read in.
 *
 * Pinned chats are lifted out of the day groups rather than marked inside them, because a
 * pin is a statement that this conversation should stop moving, and a row that stays
 * pinned but slides from Today to Previous 7 days has kept the badge and lost the point.
 * Archived chats are not here at all: the query behind this list excludes them, so
 * [DrawerSections.archived] is normally empty and exists to keep the rule true rather than
 * to be drawn. It is what guarantees that a filed conversation can never turn up among the
 * live ones if one ever reaches this function — from a search result, say, whose read
 * crossed with the archiving.
 *
 * A chat that is both pinned and archived is archived. Archiving is about whether a
 * conversation is in the list at all, and pinning only says where in the list it sits, so
 * the pin is kept, does nothing while it is filed, and means something again the moment it
 * comes back.
 */
data class DrawerSections(
    val pinned: List<ConversationSummary>,
    val days: List<DayGroup<ConversationSummary>>,
    val archived: List<ConversationSummary>,
)

/** See [DrawerSections]. Pure, so the ordering rules can be tested without a screen. */
internal fun List<ConversationSummary>.intoSections(today: LocalDate): DrawerSections {
    val (archived, live) = partition { it.isArchived }
    val (pinned, loose) = live.partition { it.isPinned }
    return DrawerSections(
        // Most recently pinned first. Not by updatedAt: see the note above about a pin
        // being the one bit of ordering the user authored.
        pinned = pinned.sortedByDescending { it.pinnedAt },
        days = loose.groupByDay(today) { it.updatedAt },
        archived = archived.sortedByDescending { it.archivedAt },
    )
}

/**
 * The app's name and mark, and the way into search.
 *
 * Two things in one row, which is what makes the branding free. Every drawer worth copying
 * opens with the product's name; ours opened with a control, and adding a row for the name
 * alone would have pushed the first conversation further down a drawer that already spent
 * four rows before reaching one. Pairing the name with the search button — which is what
 * ChatGPT does, and Claude with its own affordance — puts the identity in at no cost in
 * height, because the row it occupies is the one the search box gave back.
 *
 * The button says what it will do rather than what is on screen: a magnifier when there is
 * no box, a cross when there is one to dismiss. Both are the same control in the same
 * place, so the row never reflows.
 */
@Composable
private fun DrawerBrand(searching: Boolean, onToggleSearch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp)
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Small, because it is the second thing here rather than the first: the name is
        // what identifies the app in a list of drawers, and the tile is what confirms it.
        Mark(size = MARK_SIZE)
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f),
        )
        IconButton(onClick = onToggleSearch) {
            Icon(
                imageVector = if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                // Its own words, not the field's. The box carries a × of its own that
                // clears the term and leaves the box; this one puts the box away as well,
                // and two controls on one screen describing themselves identically while
                // doing different things is a thing a screen reader cannot untangle.
                contentDescription = stringResource(
                    if (searching) R.string.close_search else R.string.search_chats,
                ),
                modifier = Modifier.size(20.dp),
            )
        }
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
            stringResource(R.string.tools),
            Icons.Rounded.Build,
            destinations.onOpenTools,
        )
        DrawerDestination(
            stringResource(R.string.usage),
            Icons.Rounded.BarChart,
            destinations.onOpenUsage,
        )
        DrawerDestination(
            stringResource(R.string.watching),
            Icons.Rounded.Visibility,
            destinations.onOpenWatches,
        )
        DrawerDestination(
            stringResource(R.string.settings),
            Icons.Rounded.Settings,
            destinations.onOpenSettings,
        )
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
    onMenu: (Long) -> Unit,
    nowMillis: Long,
) {
    val today = Instant.ofEpochMilli(nowMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    // Keyed on both, because it sorts and groups the whole history and `nowMillis` ticks:
    // recomputing it on every frame of a drawer being dragged open is work for nothing.
    val sections = remember(conversations, today) { conversations.intoSections(today) }

    val listState = rememberLazyListState()
    // Follow a chat that has just been pinned to where it went.
    //
    // Found on a phone and by nothing else. A LazyColumn keyed by item anchors on whatever
    // is currently first on screen, so inserting a Pinned section *above* that anchor keeps
    // the list exactly where it was and puts the new section out of view above it: the row
    // left its day group, no heading appeared, and pinning read as the chat vanishing. The
    // one thing a pin promises is that the chat is now at the top, so the list goes there.
    //
    // Only when the set grows, and never on first composition — the remembered set starts
    // as whatever is already pinned, so opening the drawer does not scroll it, and neither
    // does unpinning, where the row moves back into a day group it can be seen in.
    val pinned = sections.pinned.map { it.id }
    var alreadyPinned by remember { mutableStateOf(pinned.toSet()) }
    LaunchedEffect(pinned) {
        val added = pinned.toSet() - alreadyPinned
        alreadyPinned = pinned.toSet()
        if (added.isNotEmpty()) listState.animateScrollToItem(0)
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (sections.pinned.isNotEmpty()) {
            item(key = "header-pinned") {
                SectionHeader(stringResource(R.string.pinned))
            }
            items(sections.pinned, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    isActive = conversation.id == activeId,
                    nowMillis = nowMillis,
                    onOpen = { onOpen(conversation.id) },
                    onMenu = { onMenu(conversation.id) },
                )
            }
        }

        // Grouped by the day each chat was last touched, the way every assistant
        // does it, because a flat list of forty titles is a list nobody scans.
        sections.days.forEach { group ->
            item(key = "header-${group.label}") {
                SectionHeader(group.label)
            }
            items(group.items, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    isActive = conversation.id == activeId,
                    nowMillis = nowMillis,
                    onOpen = { onOpen(conversation.id) },
                    onMenu = { onMenu(conversation.id) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * "Archived · 3", and the way to them.
 *
 * A destination rather than something that expands in place. Expanding it would put two
 * unbounded lists in one scroll container with two sets of day headings from two different
 * timelines, and a large archive could push the active history off the screen again — which
 * is the problem this row exists to fix, reintroduced one level down.
 */
@Composable
private fun ArchiveEntry(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Archive,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.archived_count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun ConversationRow(
    conversation: ConversationSummary,
    isActive: Boolean,
    nowMillis: Long,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    snippet: AnnotatedString? = null,
    /**
     * Whether the row has to say "pinned" or "archived" for itself.
     *
     * False in the list, where the heading above it already said so and repeating it on
     * every row under a heading is noise. True in search results, which have no headings.
     */
    saysWhereItIs: Boolean = false,
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
            // Long-press opens the same sheet the button does, which is how this app
            // already opens actions on one message. The button is what makes it findable;
            // the gesture is what makes it fast once it has been found once.
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
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
            //
            // Pinned and archived are words in this line rather than badges beside the
            // title, on the rows that need them at all: it is the one place on the row with
            // somewhere to put a word that costs the title no width. See [saysWhereItIs].
            Metric(
                listOfNotNull(
                    conversation.state().takeIf { saysWhereItIs },
                    // The word, not the text: a drawer row is not the place to reread a
                    // half-written message, only to know one is waiting here.
                    stringResource(R.string.draft).takeIf { conversation.hasDraft },
                    conversation.updatedAt.asRelativeTime(nowMillis),
                    conversation.modelName,
                ).joinToString(" · "),
                maxLines = 1,
            )
        }
        IconButton(onClick = onMenu) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(
                    R.string.conversation_actions_named,
                    conversation.title,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** "pinned", "archived", or nothing at all, which is what most rows are. */
@Composable
private fun ConversationSummary.state(): String? = when {
    isArchived -> stringResource(R.string.archived_marker)
    isPinned -> stringResource(R.string.pinned_marker)
    else -> null
}

/**
 * The snippet with the word that matched picked out of it.
 *
 * Two lines of prose with the reason for the row somewhere inside them is a row the eye has
 * to read rather than scan. Bold rather than a colour: the palette keeps lime for actions,
 * and a coloured word in a preview reads as a link to somewhere.
 */
internal fun String.highlighting(term: String): AnnotatedString {
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
    // Asked for by a tap, so the keyboard comes with it. A box that appears and then waits
    // to be tapped a second time is two taps to do what the button already said.
    //
    // Only when it arrives empty. A drawer reopened on a live search should show what was
    // found, not throw a keyboard over it.
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (value.isEmpty()) runCatching { requester.requestFocus() } }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .focusRequester(requester)
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
 * Archived conversations are in here, and say so on their own row. A search that could not
 * find a chat because it had been filed away would be a search nobody could trust, and
 * "which one was that" is exactly the question somebody asks about a chat they put away
 * three weeks ago.
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
    /** Every conversation, as the drawer has it now. See the note on [ConversationMatch] below. */
    live: List<ConversationSummary>,
    term: String,
    hasAnswer: Boolean,
    activeId: Long?,
    onOpen: (Long) -> Unit,
    onMenu: (Long) -> Unit,
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
            // The live row where there is one, and the match only as a fallback.
            //
            // A `ConversationMatch` is one read's answer, frozen: renaming, pinning or
            // archiving a chat from a search result would otherwise leave the row it was
            // done from showing the old name and offering the opposite action, because
            // nothing re-runs the query. The list beside it is a flow and already has the
            // truth, and the only thing the match carries that it does not is the snippet.
            ConversationRow(
                conversation = live.firstOrNull { it.id == match.id } ?: match.asSummary(),
                isActive = match.id == activeId,
                nowMillis = nowMillis,
                onOpen = { onOpen(match.id) },
                onMenu = { onMenu(match.id) },
                snippet = match.snippet?.highlighting(term),
                saysWhereItIs = true,
            )
        }
    }
}

/** One search result as a row, for when the live list has never heard of it. */
internal fun ConversationMatch.asSummary() = ConversationSummary(
    id = id,
    title = title,
    modelName = modelName,
    updatedAt = updatedAt,
    pinnedAt = pinnedAt,
    archivedAt = archivedAt,
)

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

/**
 * The brand tile beside the name.
 *
 * Cap height rather than line height: at 22dp the tile stands as tall as the letters of a
 * 19sp Display semibold, so the two read as one lockup instead of a picture with a caption.
 */
private val MARK_SIZE = 22.dp

/**
 * How far the history fades before it reaches the footer.
 *
 * A row is about 56dp, so this covers the last third of one: enough that a cut title reads
 * as continuing rather than as broken, and not so much that a row anybody wanted to read is
 * dimmed while it is still the bottom of the list.
 */
private val FADE = 20.dp

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val DAYS_PER_WEEK = 7

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun ConversationDrawerPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        ConversationDrawer(
            conversations = listOf(
                ConversationSummary(
                    1,
                    "What is a KV cache?",
                    "LFM2.5-2.6B-Q4_K_M",
                    0,
                    pinnedAt = 1,
                ),
                ConversationSummary(2, "Write a Kotlin data class", "LFM2.5-2.6B-Q4_K_M", 0),
                ConversationSummary(3, "Trip packing list", null, 0, archivedAt = 1),
            ),
            activeId = 1,
            onOpen = {},
            onNewChat = {},
            nowMillis = TimeUnit.HOURS.toMillis(3),
        )
    }
}

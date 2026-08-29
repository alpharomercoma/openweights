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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.data.db.ConversationMatch
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The drawer's overflow menu, and the two sections that made it necessary.
 *
 * What is worth asserting here is the safety of the thing that replaced a bin icon sitting
 * permanently in every row: that the menu is reachable, that delete asks before it wipes a
 * conversation, and that a chat which has been filed is not still in the list. The ordering
 * rules underneath are asserted separately, on `intoSections`, because they are arithmetic
 * and do not need a screen.
 *
 * Renaming is not here, and cannot be: a Material3 `OutlinedTextField` inside an
 * `AlertDialog` never reaches idle under Robolectric, so `waitForIdle` spins for sixty
 * seconds and throws. That reproduces in four lines with none of this app's code in them —
 * a bare field, a bare dialog, no focus and no interaction — so it is the environment
 * rather than the screen. The dialog is proved on a device instead, in
 * `ConversationRenameOnDeviceTest`, the same way the image paste is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class ConversationDrawerTest {
    @get:Rule
    val compose = createComposeRule()

    private val loose = ConversationSummary(1, "What is a KV cache?", "lfm", NOW)
    private val pinned = ConversationSummary(2, "Taxes", "lfm", NOW, pinnedAt = NOW)
    private val filed = ConversationSummary(3, "Trip packing", "lfm", NOW, archivedAt = NOW)

    @Test
    fun `the way into the archive is outside the list, and appears only when there is one`() {
        // The whole point of the rewrite. As a section at the end of the list it could only
        // be reached by scrolling past every conversation ever had; it is now above the
        // list, so where the history is scrolled to cannot hide it. And it is absent
        // entirely until something has been filed, so nobody who never archives gets a
        // control that leads to an empty room.
        var opened = false
        var shown by mutableStateOf(listOf(loose))
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ConversationDrawer(
                    conversations = shown,
                    activeId = null,
                    onOpen = {},
                    onNewChat = {},
                    nowMillis = NOW,
                    archivedCount = shown.count { it.isArchived },
                    onOpenArchive = { opened = true },
                )
            }
        }
        compose.onNodeWithText("Archived", substring = true).assertDoesNotExist()

        shown = listOf(loose, filed)

        compose.onNodeWithText("Archived · 1").performClick()
        assertThat(opened).isTrue()
    }

    @Test
    fun `an archived chat is not among the conversations`() {
        show(listOf(loose, filed))

        compose.onNodeWithText("Trip packing").assertDoesNotExist()
    }

    @Test
    fun `a pinned chat is under Pinned rather than under a day`() {
        show(listOf(loose, pinned))

        compose.onNodeWithText("Pinned").assertIsDisplayed()
        // Both are from today, so a pin that did nothing would leave one heading, not two.
        compose.onNodeWithText("Today").assertIsDisplayed()
    }

    @Test
    fun `deleting a conversation asks first`() {
        // The whole reason the bin icon left the row. It was permanently visible, one
        // mis-tap from wiping a conversation, and there is no undo anywhere on Android.
        var deleted: Long? = null
        show(listOf(loose), ConversationActions(onDelete = { deleted = it }))

        openMenuFor("What is a KV cache?")
        compose.onNodeWithText("Delete").performClick()
        assertThat(deleted).isNull()

        compose.onNodeWithText("Delete this chat?").assertIsDisplayed()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()

        assertThat(deleted).isEqualTo(1L)
    }

    @Test
    fun `the menu offers to undo whichever state the chat is already in`() {
        show(listOf(pinned))

        openMenuFor("Taxes")

        compose.onNodeWithText("Unpin").assertIsDisplayed()
        compose.onNodeWithText("Pin").assertDoesNotExist()
    }

    @Test
    fun `a chat just pinned is brought into view`() {
        // Found on a phone and by nothing else until this existed. A LazyColumn keyed by
        // item anchors on whatever is first on screen, so creating the Pinned section
        // *above* that anchor left the list exactly where it was and put the new section
        // out of view above it: the row left its day group, no heading appeared, and
        // pinning read as the chat having been deleted.
        val many = (1L..20L).map { ConversationSummary(it, "chat $it", "lfm", NOW) }
        var shown by mutableStateOf(many)
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ConversationDrawer(
                    conversations = shown,
                    activeId = null,
                    onOpen = {},
                    onNewChat = {},
                    nowMillis = NOW,
                )
            }
        }
        compose.onNodeWithText("Pinned").assertDoesNotExist()

        shown = many.map { if (it.id == 12L) it.copy(pinnedAt = NOW) else it }

        // Displayed, not merely present: off-screen rows of a LazyColumn are not composed
        // at all, so this is exactly the assertion the unfixed code fails.
        compose.onNodeWithText("Pinned").assertIsDisplayed()
        compose.onNodeWithText("chat 12").assertIsDisplayed()
    }

    @Test
    fun `an archived chat found by search can still be acted on`() {
        // The list behind the drawer no longer holds archived conversations at all, but a
        // search finds them. The overflow button on such a row used to open nothing,
        // because the sheet looked its conversation up in a list that had never heard of
        // it. It offers Unarchive, and no Pin, because a pin would move nothing.
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ConversationDrawer(
                    conversations = listOf(loose),
                    activeId = null,
                    onOpen = {},
                    onNewChat = {},
                    nowMillis = NOW,
                    search = "packing",
                    hasSearchAnswer = true,
                    results = listOf(
                        ConversationMatch(3, "Trip packing", "lfm", NOW, null, null, NOW),
                    ),
                )
            }
        }

        openMenuFor("Trip packing")

        compose.onNodeWithText("Unarchive").assertIsDisplayed()
        compose.onNodeWithText("Pin").assertDoesNotExist()
    }

    private fun openMenuFor(title: String) {
        compose.onNodeWithContentDescription("More for $title").performClick()
    }

    private fun show(
        conversations: List<ConversationSummary>,
        actions: ConversationActions = ConversationActions(),
        onOpenArchive: () -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ConversationDrawer(
                    conversations = conversations,
                    activeId = null,
                    onOpen = {},
                    onNewChat = {},
                    nowMillis = NOW,
                    actions = actions,
                    // What the view model supplies: a count, read by its own query rather
                    // than by finding archived rows in the list, because they are not in it.
                    archivedCount = conversations.count { it.isArchived },
                    onOpenArchive = onOpenArchive,
                )
            }
        }
    }

    private companion object {
        /** A real clock reading, so "Today" is today whenever the suite runs. */
        val NOW: Long = System.currentTimeMillis()
    }
}

/**
 * The ordering rules, without a screen.
 *
 * These are the part that decides what the drawer means, and they are pure, so they are
 * asserted as arithmetic: which section a row lands in, and in what order within it.
 */
class DrawerSectionsTest {
    private val today: LocalDate = LocalDate.of(2026, 8, 29)
    private val noon = 1_756_000_000_000L

    private fun chat(
        id: Long,
        updatedAt: Long = noon,
        pinnedAt: Long? = null,
        archivedAt: Long? = null,
    ) = ConversationSummary(id, "chat $id", "lfm", updatedAt, pinnedAt, archivedAt)

    @Test
    fun `a pinned chat leaves the day groups entirely`() {
        val sections = listOf(chat(1, pinnedAt = 5), chat(2)).intoSections(today)

        assertThat(sections.pinned.map { it.id }).containsExactly(1L)
        assertThat(sections.days.flatMap { it.items }.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `pinned chats are ordered by when they were pinned, newest first`() {
        // Not by updatedAt. A pin is a statement that this conversation should stop moving,
        // and ordering the section by recency would put it back at the mercy of the list.
        val sections = listOf(
            chat(1, updatedAt = noon + 1_000, pinnedAt = 5),
            chat(2, updatedAt = noon, pinnedAt = 9),
        ).intoSections(today)

        assertThat(sections.pinned.map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `a chat that is both pinned and archived is archived`() {
        // Archiving answers "is this in the list at all", pinning only answers "where in
        // the list". The pin is kept and means something again when it comes back.
        val sections = listOf(chat(1, pinnedAt = 5, archivedAt = 6)).intoSections(today)

        assertThat(sections.pinned).isEmpty()
        assertThat(sections.archived.map { it.id }).containsExactly(1L)
        assertThat(sections.archived.single().isPinned).isTrue()
    }

    @Test
    fun `archived chats are ordered by when they were filed`() {
        val sections = listOf(chat(1, archivedAt = 5), chat(2, archivedAt = 9))
            .intoSections(today)

        assertThat(sections.archived.map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `nothing at all is three empty sections rather than a header with no rows`() {
        val sections = emptyList<ConversationSummary>().intoSections(today)

        assertThat(sections.pinned).isEmpty()
        assertThat(sections.days).isEmpty()
        assertThat(sections.archived).isEmpty()
    }
}

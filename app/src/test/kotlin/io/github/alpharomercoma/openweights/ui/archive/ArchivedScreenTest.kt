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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.data.db.ConversationMatch
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.ui.chat.ConversationActions
import io.github.alpharomercoma.openweights.ui.chat.ConversationSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The archive, on its own screen.
 *
 * What is worth asserting is the behaviour that made it a screen rather than a section at
 * the end of the drawer: that it says nothing until it has read, that emptying it leaves
 * the user standing on it rather than dropping them somewhere, and that a conversation
 * filed away offers Unarchive and not a Pin that would do nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class ArchivedScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val filed = ConversationSummary(1, "Trip packing", "lfm", NOW, archivedAt = NOW)

    @Test
    fun `nothing is claimed before the archive has been read`() {
        // "Nothing is archived" while the read is still running is a wrong answer shown
        // confidently, and on a fast phone it appeared and vanished on every entry.
        show(ArchiveUiState(loaded = false))

        compose.onNodeWithText("Nothing is archived", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an empty archive says so once it knows`() {
        show(ArchiveUiState(loaded = true))

        compose.onNodeWithText("Nothing is archived", substring = true).assertIsDisplayed()
    }

    @Test
    fun `unarchiving the last one leaves the screen standing, with an empty state`() {
        // Rather than popping itself. An interface that collapses out from under the tap
        // that emptied it reads as a crash; the way back is the arrow that was always there.
        var state by mutableStateOf(ArchiveUiState(loaded = true, conversations = listOf(filed)))
        var backs = 0
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ArchivedScreen(
                    state = state,
                    onSearch = {},
                    onOpen = {},
                    actions = ConversationActions(),
                    onBack = { backs++ },
                    nowMillis = NOW,
                )
            }
        }
        compose.onNodeWithText("Trip packing").assertIsDisplayed()

        state = ArchiveUiState(loaded = true, conversations = emptyList())

        compose.onNodeWithText("Nothing is archived", substring = true).assertIsDisplayed()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `a filed conversation is not offered a pin that would do nothing`() {
        // Pinning says where a chat sits in the list; archiving says whether it is in the
        // list at all. A pin applied here moves nothing, so it is not offered.
        show(ArchiveUiState(loaded = true, conversations = listOf(filed)))

        compose.onNodeWithContentDescription("More for Trip packing").performClick()

        compose.onNodeWithText("Unarchive").assertIsDisplayed()
        compose.onNodeWithText("Pin").assertDoesNotExist()
        compose.onNodeWithText("Unpin").assertDoesNotExist()
    }

    @Test
    fun `a row still on screen from the results can be renamed after leaving the live list`() {
        // Unarchived under an open sheet, the row is gone from the live list and still in
        // the frozen results. The sheet found it there; Rename and Delete did not.
        val state = ArchiveUiState(
            loaded = true,
            query = "bag",
            hasAnswer = true,
            results = listOf(
                ConversationMatch(1, "Trip packing", "lfm", NOW, "Take the small bag.", null, NOW),
            ),
        )

        assertThat(state.conversationFor(1)?.title).isEqualTo("Trip packing")
        assertThat(state.conversationFor(2)).isNull()
    }

    @Test
    fun `a search says nothing until it has an answer`() {
        // Saying "no archived chat mentions that" while the read is still running is a
        // wrong answer shown confidently, and it appeared on every first keystroke.
        show(ArchiveUiState(loaded = true, query = "bag", hasAnswer = false))

        compose.onNodeWithText("No archived chat mentions that", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `a search that found nothing says so`() {
        show(ArchiveUiState(loaded = true, query = "bag", hasAnswer = true))

        compose.onNodeWithText("No archived chat mentions that", substring = true).assertExists()
    }

    @Test
    fun `a search result is listed with the line that matched`() {
        show(
            ArchiveUiState(
                loaded = true,
                conversations = listOf(filed),
                query = "bag",
                hasAnswer = true,
                results = listOf(
                    ConversationMatch(
                        1,
                        "Trip packing",
                        "lfm",
                        NOW,
                        "Take the small bag.",
                        null,
                        NOW,
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Take the small bag.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a result unarchived or deleted since the search stops being offered`() {
        // The results are one read's answer, frozen. A row taken out of the archive from
        // this very screen would otherwise stay in the list: still tappable, offering to be
        // unarchived a second time, or opening a conversation that has been deleted.
        val match =
            ConversationMatch(1, "Trip packing", "lfm", NOW, "Take the small bag.", null, NOW)
        show(
            ArchiveUiState(
                loaded = true,
                conversations = emptyList(),
                query = "bag",
                hasAnswer = true,
                results = listOf(match),
            ),
        )

        compose.onNodeWithText("Trip packing").assertDoesNotExist()
        compose.onNodeWithText("No archived chat mentions that", substring = true).assertExists()
    }

    private fun show(state: ArchiveUiState) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ArchivedScreen(
                    state = state,
                    onSearch = {},
                    onOpen = {},
                    actions = ConversationActions(),
                    onBack = {},
                    nowMillis = NOW,
                )
            }
        }
    }

    private companion object {
        val NOW: Long = System.currentTimeMillis()
    }
}

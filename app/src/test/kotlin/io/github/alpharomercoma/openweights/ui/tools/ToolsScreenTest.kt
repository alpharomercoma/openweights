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

package io.github.alpharomercoma.openweights.ui.tools

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.tools.GrantState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screen where the network can be switched off.
 *
 * This one is worth testing before the prettier ones, because it is the only screen in the
 * app that is a promise rather than a feature: the listing says every tool can be turned
 * off and says where each one sends what it is given, and this is where a user goes to
 * check. A regression here is not a cosmetic bug, it is the store listing becoming untrue.
 *
 * On the host, because a screen tested only on a device is a screen tested a few times a
 * month. See [io.github.alpharomercoma.openweights.ui.chat.ChatScreenTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class ToolsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `switching a tool off reports which tool and that it is off`() {
        var switched: Pair<String, Boolean>? = null
        showTools(onToggle = { id, on -> switched = id to on })

        compose.onNodeWithContentDescription("Web search").performClick()

        assertEquals("web_search" to false, switched)
    }

    @Test
    fun `switching a tool on reports it too`() {
        // The counterweight. A screen that reported every tap as "off" would pass the test
        // above and be broken in the way that matters, because the tool the user just
        // enabled would stay disabled.
        var switched: Pair<String, Boolean>? = null
        showTools(onToggle = { id, on -> switched = id to on })

        compose.onNodeWithContentDescription("Run script").performClick()

        assertEquals("run_script" to true, switched)
    }

    @Test
    fun `every tool says whether it asks before it runs`() {
        showTools()

        // Only the rows that ask say anything, which is the point: fetch_url asks every
        // time because the address is the model's choice. A line under all three saying
        // what two of them do by default is a line nobody reads, and the one that mattered
        // was hidden inside it. Counted rather than found, so a screen that went back to
        // captioning every row would fail rather than pass three times over.
        assertCount(1, "Asks before every run")
    }

    @Test
    fun `the two groups are what leaves the device and what does not`() {
        showTools()

        // The split is the only property of a tool anybody has to decide about, so it is a
        // heading rather than the last line of small print in a row.
        compose.onNodeWithText("On this device").assertIsDisplayed()
        compose.onNodeWithText("Leaves the device").assertIsDisplayed()
    }

    @Test
    fun `with no folder shared the screen offers to choose one`() {
        showTools()

        compose.onNodeWithText("No folder shared").assertIsDisplayed()
        compose.onNodeWithText("Choose a folder").assertIsDisplayed()
    }

    @Test
    fun `a folder that is held can be given back`() {
        var forgotten = false
        showTools(
            workspace = WorkspaceSummary("Documents/Notes", GrantState.READ_WRITE),
            onForgetFolder = { forgotten = true },
        )

        // The listing promises the grant can be taken back without uninstalling, so the
        // control that does it is part of the same promise as the switches above.
        compose.onNodeWithText("Documents/Notes", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()

        assert(forgotten) { "Remove must reach the view model" }
    }

    private fun assertCount(expected: Int, text: String) {
        val found = compose.onAllNodesWithText(text).fetchSemanticsNodes().size
        assert(found == expected) { "expected $expected rows saying \"$text\", found $found" }
    }

    private fun assertEquals(expected: Pair<String, Boolean>?, actual: Pair<String, Boolean>?) =
        assert(expected == actual) { "expected $expected, got $actual" }

    private fun showTools(
        workspace: WorkspaceSummary = WorkspaceSummary(null, GrantState.NONE),
        onToggle: (String, Boolean) -> Unit = { _, _ -> },
        onForgetFolder: () -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ToolsScreen(
                    state = ToolsUiState(
                        tools = listOf(
                            ToolSummary(
                                id = "web_search",
                                name = "Web search",
                                description = "Searches the web.",
                                leavesTheDevice = true,
                                asksFirst = false,
                                isReady = true,
                                isEnabled = true,
                            ),
                            ToolSummary(
                                id = "fetch_url",
                                name = "Fetch url",
                                description = "Reads a page.",
                                leavesTheDevice = true,
                                asksFirst = true,
                                isReady = true,
                                isEnabled = true,
                            ),
                            ToolSummary(
                                id = "run_script",
                                name = "Run script",
                                description = "Works something out.",
                                leavesTheDevice = false,
                                asksFirst = false,
                                isReady = true,
                                isEnabled = false,
                            ),
                        ),
                        workspace = workspace,
                    ),
                    onToggle = onToggle,
                    onChooseFolder = {},
                    onForgetFolder = onForgetFolder,
                )
            }
        }
    }
}

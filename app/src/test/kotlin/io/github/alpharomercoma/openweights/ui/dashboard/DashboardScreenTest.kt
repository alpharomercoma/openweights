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

package io.github.alpharomercoma.openweights.ui.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import io.github.alpharomercoma.openweights.core.data.ModelUsage
import io.github.alpharomercoma.openweights.core.data.UsageSummary
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The numbers, and the case where there are none.
 *
 * Everything on this screen is derived from a ledger that only ever existed on the device,
 * which is the claim the listing makes about it. What is worth testing is not the arithmetic
 * — `UsageSummaryTest` already has that — but that a summary with nothing in it renders as a
 * screen rather than as a wall of zeroes, because a fresh install is the first thing every
 * user sees and the only state nobody developing the app is ever in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class DashboardScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a fresh install says so instead of showing a wall of zeroes`() {
        // Every list is empty and every total is zero on first launch, which is the one state
        // guaranteed to happen to everybody exactly once and the one nobody developing the
        // app is ever in. The screen answers it with a sentence rather than with a grid of
        // noughts, and takes the opportunity to say where the numbers will live.
        showDashboard(UsageSummary())

        compose.onNodeWithText("Nothing yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("stay on this phone", substring = true).assertIsDisplayed()
        // And none of the tiles, because there is nothing to put in them.
        compose.onNodeWithText("Chats").assertDoesNotExist()
    }

    @Test
    fun `lifetime totals are shown`() {
        showDashboard(
            UsageSummary(
                lifetimeGeneratedTokens = 128_400,
                replies = 312,
                conversations = 27,
                activeDays = 9,
            ),
        )

        // The hero is the tokens generated, and it appears once. It used to appear twice,
        // in display type at the top and again in a tile labelled "Tokens written", which
        // is why this counts rather than merely finding it.
        assertCount(1, "128,400")
        compose.onNodeWithText("Chats").assertExists()
        compose.onNodeWithText("Days").assertExists()
        compose.onNodeWithText("Tokens written").assertDoesNotExist()
    }

    private fun assertCount(expected: Int, text: String) {
        val found = compose.onAllNodesWithText(text).fetchSemanticsNodes().size
        assert(found == expected) { "expected $expected nodes saying \"$text\", found $found" }
    }

    @Test
    fun `a model is listed with what it was used for`() {
        showDashboard(
            UsageSummary(
                replies = 60,
                perModel = listOf(
                    ModelUsage(
                        modelName = "Hammer2.1-1.5B-Q4_0",
                        generatedTokens = 41_000,
                        replies = 60,
                        averageTokensPerSecond = 13.8,
                    ),
                ),
            ),
        )

        compose.onNodeWithText("By model").assertExists()
        compose.onNodeWithText("Hammer2.1-1.5B-Q4_0", substring = true).assertExists()
        // The rate is the number this app shows and the hosted assistants do not, so it is
        // the one worth checking survives the trip to the screen.
        compose.onNodeWithText("13.8 tok/s", substring = true).assertExists()
    }

    @Test
    fun `a model with no measured rate is listed without inventing one`() {
        // averageTokensPerSecond is null until something has actually been timed. Printing
        // "0.0 tok/s" for that would be a measurement nobody took.
        showDashboard(
            UsageSummary(
                replies = 1,
                perModel = listOf(
                    ModelUsage(
                        modelName = "Qwen2.5-1.5B",
                        generatedTokens = 12,
                        replies = 1,
                        averageTokensPerSecond = null,
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Qwen2.5-1.5B", substring = true).assertExists()
        compose.onNodeWithText("0.0 tok/s", substring = true).assertDoesNotExist()
    }

    private fun showDashboard(summary: UsageSummary) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) { DashboardScreen(summary = summary) }
        }
    }
}

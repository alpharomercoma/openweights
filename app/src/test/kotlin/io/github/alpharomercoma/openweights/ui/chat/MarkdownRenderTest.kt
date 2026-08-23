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

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import io.github.alpharomercoma.openweights.core.designsystem.component.MarkdownText
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the model now writes, the app has to draw.
 *
 * The system prompt tells the model it may use headings, bullets and tables, and measured on
 * four questions the 2.6B went from no headings to a heading in every answer. That is only an
 * improvement if the renderer knows what to do with them: a table the app cannot draw reaches
 * the reader as a wall of pipes and hyphens, which is worse than the prose it replaced.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class MarkdownRenderTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * Renders and waits for the parse.
     *
     * Markdown is parsed off the composition and swapped in when it finishes, so an idle
     * check catches the empty slot it holds in the meantime. Waiting for a word that must be
     * in the output is what the listing screenshots do for the same reason.
     */
    private fun render(markdown: String, until: String) {
        compose.setContent {
            OpenWeightsTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                MarkdownText(content = markdown)
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(until, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    @Test
    fun `a table is drawn rather than printed`() {
        render(
            """
            | Vegetable | Sun |
            | --- | --- |
            | Tomato | 8 hours |
            | Lettuce | 4 hours |
            """.trimIndent(),
            until = "Tomato",
        )

        // The cells are on screen.
        listOf("Vegetable", "Sun", "Tomato", "8 hours", "Lettuce").forEach { cell ->
            check(
                compose.onAllNodesWithText(cell, substring = true)
                    .fetchSemanticsNodes().isNotEmpty(),
            ) { "the table lost the cell \"$cell\"" }
        }

        // And the syntax is not. A renderer that failed would put the delimiter row on
        // screen verbatim, which is the failure this is for.
        val drawn = compose.onRoot().printToString()
        check(!drawn.contains("---")) { "the table's delimiter row reached the screen" }
    }

    @Test
    fun `headings and bullets survive`() {
        render(
            """
            ## Soil

            - Drainage matters more than richness
            - Compost in autumn

            ### Watering

            Deeply, twice a week.
            """.trimIndent(),
            until = "Soil",
        )

        listOf("Soil", "Drainage matters", "Compost in autumn", "Watering", "twice a week")
            .forEach { text ->
                check(
                    compose.onAllNodesWithText(text, substring = true)
                        .fetchSemanticsNodes().isNotEmpty(),
                ) { "lost \"$text\"" }
            }

        val drawn = compose.onRoot().printToString()
        check(!drawn.contains("## ")) { "a heading's hashes reached the screen" }
    }
}

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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screen holds together on something other than the phone it was built on.
 *
 * Every layout number in this repository was measured at 360 x 640dp, because that is the
 * canvas the listing shots use and the one the development phone reports. That is a fine
 * place to design and a poor place to stop: a small phone, a tall one, a folded one and a
 * ten inch tablet are four different problems, and the failure they share is content wider
 * than the window, which no amount of scrolling down will fix.
 *
 * What each size asserts is the same three things, because they are the three that break:
 * nothing is wider than the screen, the composer can still be reached, and the reply is
 * still on it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AcrossScreensTest {
    @get:Rule
    val compose = createComposeRule()

    /** A small phone still sold in volume, and the narrowest Android that matters. */
    @Test
    @Config(qualifiers = "w320dp-h534dp-night-xhdpi")
    fun smallPhone() = holdsTogether()

    /** The canvas everything else here was measured on. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
    fun ordinaryPhone() = holdsTogether()

    /** A modern tall phone, where the extra height is all transcript. */
    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
    fun tallPhone() = holdsTogether()

    /** A book-style foldable, open. Wider than the reading cap, so the cap has to hold. */
    @Test
    @Config(qualifiers = "w674dp-h841dp-night-xhdpi")
    fun foldableOpen() = holdsTogether()

    /** Seven inches. */
    @Test
    @Config(qualifiers = "w600dp-h960dp-night-xhdpi")
    fun sevenInchTablet() = holdsTogether()

    /** Ten inches, the widest the listing ships. */
    @Test
    @Config(qualifiers = "w900dp-h1280dp-night-xhdpi")
    fun tenInchTablet() = holdsTogether()

    /** A phone on its side, where the composer and the keyboard fight for the height. */
    @Test
    @Config(qualifiers = "w915dp-h412dp-night-xxhdpi")
    fun phoneLandscape() = holdsTogether()

    private fun holdsTogether() {
        stage()

        val root = compose.onRoot().fetchSemanticsNode()
        val width = root.size.width

        // Nothing sticks out sideways. A row that overflows is invisible on a phone and
        // unreachable everywhere, and it is the one failure a taller screen never reveals.
        fun check(node: androidx.compose.ui.semantics.SemanticsNode) {
            val right = node.positionInRoot.x + node.size.width
            check(right <= width + SLACK) {
                "something reaches ${right.toInt()}px on a ${width}px screen"
            }
            node.children.forEach(::check)
        }
        check(root)

        // The two things that must be on screen whatever the size.
        compose.onNodeWithContentDescription("Message").assertExists()
        check(
            compose.onAllNodesWithText(ANSWER, substring = true).fetchSemanticsNodes()
                .isNotEmpty(),
        ) { "the reply is not on a ${width}px screen" }
    }

    private fun stage() {
        compose.setContent {
            OpenWeightsTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                ChatScreen(
                    state = ChatUiState(
                        modelName = "LFM2.5-2.6B-QAD-Q4_0",
                        modelQuantization = "lfm2 2.6B Q4_0",
                        transcript = listOf(
                            TranscriptEntry(
                                id = 1,
                                role = ChatRole.USER,
                                // Long and unbroken, which is what finds an overflow.
                                text = "Which of my notes mention the September deadline, " +
                                    "and what does notes/handover.md say about watering?",
                            ),
                            TranscriptEntry(
                                id = 2,
                                role = ChatRole.ASSISTANT,
                                text = ANSWER,
                                answer = ANSWER,
                                tokensPerSecond = 16.4,
                                timeToFirstTokenMs = 210,
                                generatedTokens = 120,
                                totalMillis = 7_400,
                            ),
                        ),
                        contextUsed = 1_640,
                        contextSize = 4096,
                        supportsTools = true,
                        toolsAvailable = true,
                    ),
                    onSend = { true },
                    onStop = {},
                    onRegenerate = {},
                    onNewChat = {},
                    onCompact = {},
                    plan = null,
                    onTickStep = {},
                    question = null,
                    onAnswerQuestion = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(ANSWER, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private companion object {
        const val ANSWER = "Both notes mention it, and the deadline is 14 September."

        /** A pixel of rounding, not a licence to overflow. */
        const val SLACK = 1
    }
}

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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the app shows for something it did on the user's behalf.
 *
 * A tool run is the one part of a turn the user did not ask for in words: the model decided
 * it, and something left the device or touched a file as a result. The chip is the whole
 * account of that, so the two things worth pinning are that it names the tool and that what
 * came back can be read rather than taken on trust.
 *
 * The three states are asserted apart because they are three different claims — asked for,
 * ran, refused — and a chip that reported a skipped call as a completed one would be the
 * app lying about what it did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class ToolStepBlockTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a tool that ran says what it did, in words`() {
        showStep(AgentStep.Ran(call("web_search"), result = "Manila: 31C.", millis = 1_840))

        // "web_search" is a function call; "Searched the web for" is what happened. This
        // row is the whole of the disclosure that anything left the phone, and a
        // disclosure the reader has to decode is not one.
        compose.onNodeWithText("Searched the web for", substring = true).assertIsDisplayed()
        compose.onNodeWithText("web_search", substring = true).assertDoesNotExist()
    }

    @Test
    fun `what a tool returned is hidden until it is asked for`() {
        // A result can be four thousand characters. Showing it inline would bury the answer
        // the user actually wanted underneath the working.
        showStep(AgentStep.Ran(call("web_search"), result = "Manila: 31C.", millis = 1_840))

        compose.onNodeWithText("Manila: 31C.").assertDoesNotExist()

        compose.onNodeWithText("Searched the web for", substring = true).performClick()

        compose.onNodeWithText("Manila: 31C.").assertIsDisplayed()
    }

    @Test
    fun `a call that was only requested does not claim to have run`() {
        showStep(AgentStep.Requested(call("fetch_url")))

        compose.onNodeWithText("requested", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a call that was refused says so and says why`() {
        // The user declining a tool and the tool having run are opposite outcomes, and the
        // reason is the part that makes a refusal readable rather than a shrug.
        showStep(AgentStep.Skipped(call("fetch_url"), why = "The user declined to run it."))

        compose.onNodeWithText("skipped", substring = true).assertIsDisplayed()
        compose.onNodeWithText("declined", substring = true).assertIsDisplayed()
    }

    private fun call(name: String) = ToolCall(id = name, name = name, argumentsJson = "{}")

    private fun showStep(step: AgentStep) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) { ToolStepBlock(step = step) }
        }
    }

    @Test
    fun `a program is shown as code rather than as a paragraph`() {
        // The complaint this answers: the sandbox's output read like a web search result.
        // A program rendered as prose has no language, no colours and nothing to copy, and
        // copying is the whole reason to show generated code to a person at all.
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ToolStepBlock(
                    step = AgentStep.Ran(
                        call = ToolCall(
                            id = "1",
                            name = "run_script",
                            argumentsJson = """{"source":"const total = 6 * 7;\ntotal"}""",
                        ),
                        result = "42",
                        millis = 12,
                    ),
                )
            }
        }

        compose.onNodeWithText("Worked out", substring = true).performClick()

        // The program itself, which the old rendering never showed: the plain path printed
        // only what came back, so a script that failed was a message with no code beside it.
        // Waited for rather than asserted straight away: Markdown parses off the composition
        // and keeps the previous render while it does, so a bare assertion here passes or
        // fails on timing rather than on behaviour.
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("const total", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Result", substring = true).assertExists()
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("42", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `an ordinary tool still shows its result as text`() {
        // The counterweight. Only the sandbox gets the editor treatment; a search result is
        // prose and should stay prose.
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ToolStepBlock(
                    step = AgentStep.Ran(
                        call = ToolCall(
                            id = "1",
                            name = "web_search",
                            argumentsJson = """{"query":"tides"}""",
                        ),
                        result = "High tide is at four.",
                        millis = 12,
                    ),
                )
            }
        }

        compose.onNodeWithText("Searched", substring = true).performClick()

        compose.onNodeWithText("High tide is at four.", substring = true).assertExists()
    }

    @Test
    fun `only a web address is handed to the system on a picture tap`() {
        // The source of a picture is a field in a search provider's JSON, which is to say
        // whatever the page said it was. ACTION_VIEW opens whichever app owns the scheme, so
        // anything that is not a web page must not be launched from a tap on a thumbnail.
        assertTrue(isWebAddress("https://example.com/page"))
        assertTrue(isWebAddress("HTTP://example.com/page"))
        assertFalse(isWebAddress("tel:+1234567890"))
        assertFalse(isWebAddress("sms:+1234567890?body=hi"))
        assertFalse(isWebAddress("market://details?id=x"))
        assertFalse(isWebAddress("content://io.github.alpharomercoma.openweights.files/x"))
        assertFalse(isWebAddress("javascript:alert(1)"))
        assertFalse(isWebAddress(""))
    }

    private companion object {
        /** Long enough for a Markdown parse on a slow build agent, short enough to fail fast. */
        const val WAIT_MS = 5_000L
    }
}

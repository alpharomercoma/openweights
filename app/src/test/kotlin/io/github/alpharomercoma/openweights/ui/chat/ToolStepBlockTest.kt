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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.tools.AgentStep
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
    fun `a tool that ran names itself`() {
        showStep(AgentStep.Ran(call("web_search"), result = "Manila: 31C.", millis = 1_840))

        compose.onNodeWithText("web_search", substring = true).assertIsDisplayed()
    }

    @Test
    fun `what a tool returned is hidden until it is asked for`() {
        // A result can be four thousand characters. Showing it inline would bury the answer
        // the user actually wanted underneath the working.
        showStep(AgentStep.Ran(call("web_search"), result = "Manila: 31C.", millis = 1_840))

        compose.onNodeWithText("Manila: 31C.").assertDoesNotExist()

        compose.onNodeWithText("web_search", substring = true).performClick()

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
}

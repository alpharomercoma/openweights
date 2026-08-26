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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A mode chosen by typing had no way back except knowing to type the opposite.
 *
 * Nothing on screen said the label was a button, and tapping the bar it sat in opened the
 * model picker instead, which is a different screen entirely. This is the fix: the mode
 * label is its own target, and tapping it is what turns it off.
 */
@RunWith(RobolectricTestRunner::class)
class RuntimeBarTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the default mode has no label to tap`() {
        show(mode = AgentMode.AUTO)

        AgentMode.entries.forEach { mode ->
            compose.onNodeWithText(mode.label).assertDoesNotExist()
        }
    }

    @Test
    fun `a mode that is not the default offers a way to leave it`() {
        show(mode = AgentMode.PLAN)

        compose.onNodeWithText(AgentMode.PLAN.label).assertIsDisplayed()
        // Said to a screen reader, since the visible word alone is not a sentence.
        compose.onNodeWithContentDescription("Leave ${AgentMode.PLAN.label} mode")
            .assertIsDisplayed()
    }

    @Test
    fun `tapping the mode label resets the mode without opening the model picker`() {
        var resetCalls = 0
        var pickerOpened = false
        show(
            mode = AgentMode.PLAN,
            onResetMode = { resetCalls++ },
            onClick = { pickerOpened = true },
        )

        compose.onNodeWithText(AgentMode.PLAN.label).performClick()

        assert(resetCalls == 1) { "tapping the mode label must reset the mode exactly once" }
        assert(!pickerOpened) { "resetting the mode must not also open the model picker" }
    }

    @Test
    fun `a goal running its own steps hides the control, not merely disables it`() {
        // PLAN is what a goal's planning turn runs in, and AUTO, ASK or YOLO is what its
        // steps run in. Either way the mode belongs to the run for as long as it is going,
        // and a tap landing here would hand it back mid-run: PLAN loses the plan it was
        // about to read back, and AUTO on a run started in ASK or YOLO reinstates the
        // checks that mode had waived. Hidden rather than disabled, so a tap in flight
        // reaches nothing rather than a button that silently declines it.
        show(mode = AgentMode.PLAN, goalRunning = true)

        compose.onNodeWithText(AgentMode.PLAN.label).assertDoesNotExist()
    }

    private fun show(
        mode: AgentMode,
        goalRunning: Boolean = false,
        onResetMode: () -> Unit = {},
        onClick: () -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                RuntimeBar(
                    state = ChatUiState(
                        modelName = "LFM2.5-1.2B-Q4_K_M",
                        backend = "CPU",
                        contextSize = 4096,
                        mode = mode,
                    ),
                    onClick = onClick,
                    onResetMode = onResetMode,
                    goalRunning = goalRunning,
                )
            }
        }
    }
}

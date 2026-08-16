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
import androidx.compose.ui.test.performScrollTo
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The controls that are only offered when they do something.
 *
 * This sheet is where the app's habit of measuring before offering shows up as user
 * interface. Thinking is shown only for a template that can be told not to think, and the
 * processor picker only where there is something besides the CPU to pick, because both were
 * decided by rendering the template twice and comparing rather than by reading the model's
 * name. A control that is present and inert is worse than a missing one: it is a promise the
 * weights never made, and the user is the one who finds out.
 *
 * So what is asserted here is mostly absence, which is the awkward half to test and the half
 * that regresses quietly. A stray `true` in a view model would light every control on the
 * sheet and nothing else in the suite would say a word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class ParameterSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a model whose template ignores thinking is not offered the switch`() {
        showSheet(supportsThinking = false)

        // Measured at load by rendering the template with the flag both ways: a template
        // that produces the same prompt either way cannot be told anything, so offering the
        // control would be offering a setting that changes nothing.
        compose.onNodeWithText("Thinking", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a model whose template reads it is offered the switch`() {
        // The counterweight. A sheet that hid the control unconditionally would pass the
        // test above and take a working feature away from every model that has it.
        showSheet(supportsThinking = true)

        compose.onNodeWithText("Thinking", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a device with nothing but a CPU is not offered a processor`() {
        showSheet(hasGpu = false)

        compose.onNodeWithText("Processor").assertDoesNotExist()
    }

    @Test
    fun `a device with a second backend is offered the choice`() {
        showSheet(hasGpu = true)

        compose.onNodeWithText("Processor").assertIsDisplayed()
    }

    @Test
    fun `the sheet edits a draft and only commits it on save`() {
        // The sheet holds a draft rather than writing through on every slider movement,
        // which is what makes Reset meaningful and what stops a half-dragged value being
        // saved for a model. Nothing should reach the caller until Save.
        var saved: ModelPreferences? = null
        showSheet(onSave = { saved = it })

        compose.onNodeWithText("Context length").assertIsDisplayed()
        assert(saved == null) { "opening the sheet must not save anything, got $saved" }

        compose.onNodeWithText("Save").performScrollTo().performClick()

        assert(saved != null) { "Save must reach the caller" }
    }

    @Test
    fun `resetting is offered and reaches the caller`() {
        var reset = false
        showSheet(onReset = { reset = true })

        compose.onNodeWithText("Reset to defaults").performScrollTo().performClick()

        assert(reset) { "Reset must reach the caller" }
    }

    @Suppress("LongParameterList")
    private fun showSheet(
        supportsThinking: Boolean = false,
        hasGpu: Boolean = false,
        onSave: (ModelPreferences) -> Unit = {},
        onReset: () -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ParameterSheet(
                    modelName = "LFM2.5-2.6B-Q4_K_M",
                    preferences = ModelPreferences(),
                    supportsThinking = supportsThinking,
                    hasGpu = hasGpu,
                    onSave = onSave,
                    onReset = onReset,
                    onDismiss = {},
                )
            }
        }
    }
}

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

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.alpharomercoma.openweights.core.common.model.OutputModality
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

        compose.onNodeWithText("Reading the prompt").assertDoesNotExist()
        compose.onNodeWithText("Writing the answer").assertDoesNotExist()
    }

    @Test
    fun `a device with a second backend is offered the choice for each half of a turn`() {
        showSheet(hasGpu = true)

        // Two controls, because the halves want opposite things: a GPU reads a prompt
        // faster and writes an answer slower, so "read on the GPU, write on the CPU" is a
        // real answer and a single control could not express it.
        //
        // Behind Advanced, with the other settings whose defaults are right. Still
        // reachable, which is the difference between folding an interface up and cutting
        // things out of it.
        compose.onNodeWithText("Reading the prompt").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Reading the prompt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Writing the answer").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the NPU is not offered on a device that has none`() {
        showSheet(hasGpu = true)
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Reading the prompt").performScrollTo().assertIsDisplayed()

        // Offering it would be a button that changes nothing: llama.cpp has no vendor NPU
        // backend compiled in, and a compiled model's processor is fixed when it is
        // exported. It appears only where the engine enumerates an accelerator.
        compose.onAllNodesWithText("NPU").assertCountEquals(0)
    }

    @Test
    fun `the settings people actually touch are not behind the disclosure`() {
        showSheet()

        compose.onNodeWithText("Temperature").assertIsDisplayed()
        compose.onNodeWithText("Context length").assertIsDisplayed()
        compose.onNodeWithText("System prompt").assertIsDisplayed()
        // And the ones whose defaults are correct are not in the way of them.
        compose.onNodeWithText("Top-p").assertDoesNotExist()
        compose.onNodeWithText("Repeat penalty").assertDoesNotExist()
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

    @Test
    fun `a speech model is not offered the settings it cannot read`() {
        // Verified against the engine rather than assumed: `mtmd_helper_gen_audio_inp` has
        // fields for top-k, top-p and a seed, and none for any of these. Drawing them would
        // put four controls on screen with nothing on the other end of them.
        showSheet(supportsThinking = true, outputModality = OutputModality.SPEECH)

        compose.onNodeWithText("Temperature").assertDoesNotExist()
        compose.onNodeWithText("Answer length").assertDoesNotExist()
        compose.onNodeWithText("System prompt").assertDoesNotExist()
        compose.onNodeWithText("Thinking", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a speech model keeps the two samplers it does read`() {
        // The counterweight, and the reason this is a table rather than a blanket rule: a
        // sheet that hid everything for speech would be as wrong as one that hid nothing.
        showSheet(outputModality = OutputModality.SPEECH)

        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Top-p").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Top-k").performScrollTo().assertIsDisplayed()
        // Not in the struct, so not on the sheet, even under Advanced.
        compose.onNodeWithText("Repeat penalty").assertDoesNotExist()
    }

    @Test
    fun `the sheet no longer claims settings are per model`() {
        // They stopped being per model when hyperparameters went global, and the caption
        // saying otherwise outlived the change.
        showSheet()

        compose.onNodeWithText("saved for this model only").assertDoesNotExist()
    }

    @Suppress("LongParameterList")
    private fun showSheet(
        supportsThinking: Boolean = false,
        hasGpu: Boolean = false,
        outputModality: OutputModality = OutputModality.TEXT,
        onSave: (ModelPreferences) -> Unit = {},
        onReset: () -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ParameterSheet(
                    modelName = "LFM2.5-2.6B-Q4_K_M",
                    preferences = ModelPreferences(),
                    supportsThinking = supportsThinking,
                    outputModality = outputModality,
                    hasGpu = hasGpu,
                    onSave = onSave,
                    onReset = onReset,
                    onDismiss = {},
                )
            }
        }
    }
}

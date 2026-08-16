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
import io.github.alpharomercoma.openweights.core.common.model.ReasoningEffort
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A control that appears only where it does something.
 *
 * Both flags behind this were decided by rendering the model's template twice and comparing
 * the bytes, rather than by reading its name, because a template that ignores the thinking
 * flag produces the same prompt either way and a switch over it would be a lie in the shape
 * of a setting. LFM2.5 is the case that made this necessary: llama.cpp's own capability
 * check says it supports thinking, and it reasons anyway with the flag off, four times out
 * of four.
 *
 * So the assertions here are mostly about absence, which is the half that regresses without
 * anyone noticing: a control that has quietly stopped appearing looks the same as a model
 * that never had it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class ThinkingControlTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a model that cannot be told either thing shows nothing at all`() {
        showControl(supportsThinking = false, supportsEffort = false)

        compose.onNodeWithText("Think", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Effort", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a model that can be told not to think is offered that and only that`() {
        showControl(supportsThinking = true, supportsEffort = false)

        compose.onNodeWithText("Think", substring = true).assertIsDisplayed()
        // Effort is a separate capability, measured separately. Offering it here would be
        // offering a dial that moves nothing.
        compose.onNodeWithText("Effort", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a model that reads the effort argument is offered the dial`() {
        showControl(supportsThinking = true, supportsEffort = true)

        compose.onNodeWithText("Effort", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the effort in use is the one that was passed in`() {
        showControl(supportsThinking = true, supportsEffort = true, effort = ReasoningEffort.HIGH)

        compose.onNodeWithText("high", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    @Suppress("LongParameterList")
    private fun showControl(
        supportsThinking: Boolean,
        supportsEffort: Boolean,
        effort: ReasoningEffort = ReasoningEffort.DEFAULT,
        thinking: Boolean = true,
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ThinkingControl(
                    supportsEffort = supportsEffort,
                    supportsThinking = supportsThinking,
                    effort = effort,
                    thinking = thinking,
                    enabled = true,
                    onEffort = {},
                    onThinking = {},
                )
            }
        }
    }
}

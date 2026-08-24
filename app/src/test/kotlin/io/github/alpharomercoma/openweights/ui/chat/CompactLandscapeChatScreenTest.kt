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
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A compact landscape viewport is still a valid window during rotation transitions and on
 * desktop-style Android environments. The empty state must keep its copy and composer inside
 * that short viewport instead of drawing the lower half of the greeting behind the composer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w640dp-h360dp-night-xxhdpi")
class CompactLandscapeChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun welcomeCopyAndComposerRemainDisplayedInCompactLandscape() {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ChatScreen(
                    state = ChatUiState(
                        modelName = "LFM2.5-1.2B-Instruct-QAD-Q4_0",
                        contextSize = 32_768,
                    ),
                    onSend = { true },
                    onStop = {},
                    onRegenerate = {},
                    onNewChat = {},
                    onCompact = {},
                )
            }
        }

        compose.onNodeWithText("Where shall we start?").assertIsDisplayed()
        compose.onNodeWithText("Whatever you ask is answered on this phone.")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Message").assertIsDisplayed()
    }
}

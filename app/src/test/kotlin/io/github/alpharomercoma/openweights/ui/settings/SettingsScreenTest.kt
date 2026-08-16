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

package io.github.alpharomercoma.openweights.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.alpharomercoma.openweights.core.data.ThemeChoice
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The screen that holds the one secret this app has.
 *
 * A Hugging Face token is optional and most people never set one, which is exactly why the
 * handling of it is worth pinning down: a path nobody walks is a path nobody notices
 * breaking. The privacy policy makes three claims about it — that it is encrypted with a key
 * in the Android Keystore, that it goes only to Hugging Face, and that removing it removes
 * it — and the first two are enforced below this screen. The third is a button here.
 *
 * The assertion this file exists for is the one about echoing. `SettingsUiState` carries
 * `hasToken` and no token, so the screen has nothing to show back even if it wanted to, and
 * that is a property worth a test rather than a comment: it is one field away from being
 * untrue, and the field would look like a convenience.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a typed token reaches the vault rather than the screen`() {
        var saved: String? = null
        showSettings(onSaveToken = { saved = it })

        // The field itself, not its label: an outlined field renders the two as separate
        // nodes and only one of them accepts text. There is one editable field on this
        // screen, which is what makes this unambiguous.
        compose.onNode(hasSetTextAction()).performTextInput("hf_example")
        compose.onNodeWithText("Save and verify").performClick()

        assert(saved == "hf_example") { "expected the typed token to be saved, got $saved" }
    }

    @Test
    fun `a saved token is never shown back`() {
        // The screen is told whether a token exists, never what it is. Somebody adding the
        // value to the state so the field could be pre-filled would be adding a plaintext
        // secret to a Compose state holder, and it would look like a courtesy.
        showSettings(hasToken = true)

        compose.onNodeWithText("hf_", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Replace saved token").assertIsDisplayed()
    }

    @Test
    fun `a saved token can be removed`() {
        // The policy says removing it removes it, and this is the control that promise
        // reduces to.
        var cleared = false
        showSettings(hasToken = true, onClearToken = { cleared = true })

        compose.onNodeWithText("Remove").performClick()

        assert(cleared) { "Remove must reach the vault" }
    }

    @Test
    fun `choosing a theme reports the choice`() {
        var chosen: ThemeChoice? = null
        showSettings(onSelectTheme = { chosen = it })

        compose.onNodeWithText("Light").performClick()

        assert(chosen == ThemeChoice.LIGHT) { "expected LIGHT, got $chosen" }
    }

    @Test
    fun `the theme in use is the one the state names`() {
        // Read off the repository rather than remembered here, so the setting survives the
        // screen being closed.
        showSettings(theme = ThemeChoice.DARK)

        compose.onNodeWithText("Dark").assertIsDisplayed()
        compose.onNodeWithText("System").assertIsDisplayed()
    }

    private fun showSettings(
        hasToken: Boolean = false,
        theme: ThemeChoice = ThemeChoice.SYSTEM,
        onSelectTheme: (ThemeChoice) -> Unit = {},
        onSaveToken: (String) -> Unit = {},
        onClearToken: () -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                SettingsScreen(
                    state = SettingsUiState(hasToken = hasToken, theme = theme),
                    onSelectTheme = onSelectTheme,
                    onSaveToken = onSaveToken,
                    onClearToken = onClearToken,
                )
            }
        }
    }
}

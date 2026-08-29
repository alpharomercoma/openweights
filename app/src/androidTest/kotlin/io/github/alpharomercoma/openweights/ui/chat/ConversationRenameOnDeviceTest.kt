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

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rename dialog, typed into, on a device.
 *
 * On a device and not by choice. A Material3 `OutlinedTextField` inside an `AlertDialog`
 * never reaches idle under Robolectric — a bare field in a bare dialog, no focus and no
 * interaction, is enough to make `waitForIdle` spin for sixty seconds and throw — so the
 * whole of this dialog is untestable on the host and only this half of the feature had to
 * move. What the repository does with the string it is handed (blank refused, whitespace
 * collapsed, cut to a title's length) is asserted on the host, in `ChatRepositoryTest`.
 */
@RunWith(AndroidJUnit4::class)
class ConversationRenameOnDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val conversation = ConversationSummary(1, "What is a KV cache?", "lfm", 0)

    @Test
    fun theNameTypedIsTheNameHandedBack() {
        var renamed: String? = null
        show(onRename = { renamed = it })

        compose.onNodeWithTag(RENAME_FIELD).performTextReplacement("Cache notes")
        compose.onNodeWithText("Save").performClick()

        assertThat(renamed).isEqualTo("Cache notes")
    }

    @Test
    fun theDialogOpensOnTheNameItIsAboutToChange() {
        // Selected, not merely present: renaming is usually retyping, and a caret at the
        // end would make that thirty backspaces on a phone keyboard.
        show()

        compose.onNodeWithText("What is a KV cache?").assertExists()
    }

    @Test
    fun aNameOfNothingCannotBeSaved() {
        // The other half of the repository's refusal. A row with an empty title cannot be
        // told from the one above it, and there is no undo anywhere on Android, so the
        // button says so before it is tapped rather than swallowing the tap afterwards.
        show()

        compose.onNodeWithTag(RENAME_FIELD).performTextReplacement("   ")

        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    private fun show(onRename: (String) -> Unit = {}) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                RenameConversationDialog(
                    conversation = conversation,
                    onRename = onRename,
                    onDismiss = {},
                )
            }
        }
    }
}

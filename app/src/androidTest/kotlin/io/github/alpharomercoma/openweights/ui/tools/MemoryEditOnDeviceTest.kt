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

package io.github.alpharomercoma.openweights.ui.tools

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
 * The memory edit dialog, typed into, on a device.
 *
 * On a device for the same reason as `ConversationRenameOnDeviceTest`, and not by choice:
 * a Material3 `OutlinedTextField` inside an `AlertDialog` cannot run under Robolectric —
 * composing one loops in layout until the heap goes. The host half of the feature lives in
 * `ToolsScreenTest`, which stops at the edit affordance, and in `MemoryTest`, which owns
 * what `Memory.replace` does with the strings this dialog hands back.
 */
@RunWith(AndroidJUnit4::class)
class MemoryEditOnDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theEditedTextIsHandedBackWithTheOriginal() {
        var saved: String? = null
        show(onSave = { saved = it })

        compose.onNodeWithTag(MEMORY_EDIT_FIELD).performTextReplacement("Prefers Rust")
        compose.onNodeWithText("Save").performClick()

        assertThat(saved).isEqualTo("Prefers Rust")
    }

    @Test
    fun theDialogOpensOnTheFactItIsAboutToChange() {
        show()

        compose.onNodeWithText("Prefers Kotlin").assertExists()
    }

    @Test
    fun aFactTooLongToKeepCannotBeSavedEither() {
        // Memory.replace would reject it, and a Save that closes the dialog while saving
        // nothing is a rejection nobody was shown. The button and the store must agree.
        show()

        compose.onNodeWithTag(MEMORY_EDIT_FIELD).performTextReplacement("y".repeat(161))

        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun aFactOfNothingCannotBeSaved() {
        // Memory.replace would refuse the blank anyway; the button saying so first means
        // the refusal never has to be read back out of a dialog that already closed.
        show()

        compose.onNodeWithTag(MEMORY_EDIT_FIELD).performTextReplacement("   ")

        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    private fun show(onSave: (String) -> Unit = {}) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                MemoryEditDialog(
                    original = "Prefers Kotlin",
                    onSave = onSave,
                    onDismiss = {},
                )
            }
        }
    }
}

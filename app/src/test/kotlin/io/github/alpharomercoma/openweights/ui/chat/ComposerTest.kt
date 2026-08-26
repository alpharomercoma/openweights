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

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.model.StagedDocument
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A command chosen from the palette, and what Send does with a word that only looks like one.
 *
 * `/deep-research` typed with a space where the trigger has a hyphen used to reach the model
 * as an ordinary question, with nothing on screen to say a command had even been attempted.
 * The palette path is tested here because it is the one immune to that: once a command is
 * chosen this way, autocorrect or a stray edit to the argument cannot touch the trigger, since
 * the trigger is no longer characters in the field at all.
 */
@RunWith(RobolectricTestRunner::class)
class ComposerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `choosing an argument command from the palette does not run it empty`() {
        var dispatched: SlashCommand? = null
        show(onCommand = { dispatched = it })

        compose.onNodeWithContentDescription("Message").performTextInput("/deep")
        compose.onNodeWithText(SlashCommand.DEEP_RESEARCH.description).performClick()

        // Not yet: an argument command chosen from the palette waits for its argument
        // rather than running with nothing, which is what silently did nothing before.
        assert(dispatched == null) { "an argument command must not dispatch before it has one" }
        compose.onNodeWithText(SlashCommand.DEEP_RESEARCH.trigger).assertExists()
    }

    @Test
    fun `sending after choosing a command from the palette carries the typed argument`() {
        var sent: String? = null
        show(onSend = {
            sent = it
            true
        })

        compose.onNodeWithContentDescription("Message").performTextInput("/deep")
        compose.onNodeWithText(SlashCommand.DEEP_RESEARCH.description).performClick()
        compose.onNodeWithContentDescription("Message")
            .performTextInput("what changed in Android 16")
        compose.onNodeWithContentDescription("Send message").performClick()

        assert(sent == "${SlashCommand.DEEP_RESEARCH.trigger} what changed in Android 16") {
            "expected the trigger and the typed argument together, got: $sent"
        }
    }

    @Test
    fun `removing the chosen command keeps whatever argument was already typed`() {
        show()

        compose.onNodeWithContentDescription("Message").performTextInput("/deep")
        compose.onNodeWithText(SlashCommand.DEEP_RESEARCH.description).performClick()
        compose.onNodeWithContentDescription("Message").performTextInput("a half-written question")
        compose.onNodeWithContentDescription("Remove ${SlashCommand.DEEP_RESEARCH.trigger} command")
            .performClick()

        // The command is gone; the sentence written for it is not.
        compose.onNodeWithText(SlashCommand.DEEP_RESEARCH.trigger).assertDoesNotExist()
        compose.onNodeWithText("a half-written question").assertExists()
    }

    @Test
    fun `a no-argument command from the palette still runs immediately`() {
        var dispatched: SlashCommand? = null
        show(onCommand = { dispatched = it })

        compose.onNodeWithContentDescription("Message").performTextInput("/auto")
        // Not the trigger text: the field itself now also reads "/auto" and matching on
        // that finds both. The description is unique to the palette row.
        compose.onNodeWithText(SlashCommand.AUTO.description).performClick()

        assert(dispatched == SlashCommand.AUTO) {
            "expected AUTO to run immediately, got: $dispatched"
        }
    }

    @Test
    fun `a slash typed by hand that matches nothing warns before it sends`() {
        var sent: String? = null
        show(onSend = {
            sent = it
            true
        })

        // A space instead of the hyphen: the exact typo that motivated this.
        compose.onNodeWithContentDescription("Message").performTextInput("/deep research foo")
        compose.onNodeWithContentDescription("Send message").performClick()

        assert(sent == null) {
            "an unrecognised command-shaped text must not send on the first tap"
        }
        compose.onNodeWithText(SlashCommand.DEEP_RESEARCH.trigger, substring = true).assertExists()

        // Pressing again, with the text unchanged, is the user overruling the warning.
        compose.onNodeWithContentDescription("Send message").performClick()
        assert(sent == "/deep research foo") { "a second press must send the text as written" }
    }

    @Test
    fun `accepting a suggestion for a no-argument command runs it rather than staging it`() {
        var dispatched: SlashCommand? = null
        var sent: String? = null
        show(onCommand = { dispatched = it }, onSend = {
            sent = it
            true
        })

        // A near miss of /plan, which takes no argument.
        compose.onNodeWithContentDescription("Message").performTextInput("/pl an")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithText("Did you mean ${SlashCommand.PLAN.trigger}?").performClick()

        assert(dispatched == SlashCommand.PLAN) {
            "expected PLAN to run immediately from the suggestion, got: $dispatched"
        }
        assert(sent == null) { "a no-argument suggestion must not also reach onSend as prose" }
    }

    @Test
    fun `accepting a suggestion for an argument command keeps the whole question`() {
        show()

        // The exact input that motivated this: a space where the trigger has a hyphen,
        // with a real question after it.
        compose.onNodeWithContentDescription("Message")
            .performTextInput("/deep research what changed")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithText("Did you mean ${SlashCommand.DEEP_RESEARCH.trigger}?")
            .performClick()

        // Not "research what changed" — the whole near-miss trigger comes off, not just
        // the first word of it.
        compose.onNodeWithText("what changed").assertExists()
    }

    @Test
    fun `a suggestion cannot be accepted once the composer becomes disabled`() {
        // The suggestion button is a second way into the view model beside Send and the
        // palette, both of which already stop while a reply or a goal is running. A model
        // that starts loading, or a goal that starts, right after the warning appeared must
        // not leave a stray tap free to run a no-argument suggestion immediately.
        var dispatched: SlashCommand? = null
        val enabled = mutableStateOf(true)
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Composer(
                    conversationKey = null,
                    enabled = enabled.value,
                    isGenerating = false,
                    staged = emptyList<MessagePart.File>(),
                    document = null as StagedDocument?,
                    onRemoveDocument = {},
                    isAttaching = false,
                    canDictate = false,
                    isListening = false,
                    heard = "",
                    onAttach = {},
                    onRemoveStaged = {},
                    onDictate = {},
                    onSend = { true },
                    onStop = {},
                    onCommand = { dispatched = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Message").performTextInput("/pl an")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithText("Did you mean ${SlashCommand.PLAN.trigger}?").assertIsDisplayed()

        enabled.value = false
        compose.onNodeWithText("Did you mean ${SlashCommand.PLAN.trigger}?").performClick()

        assert(dispatched == null) {
            "a disabled suggestion must not dispatch even when the button is still on screen"
        }
    }

    @Test
    fun `editing a message that looks like a failed command resends on the first press`() {
        var sent: String? = null
        show(editing = "/tmp is full", onSend = {
            sent = it
            true
        })

        compose.onNodeWithContentDescription("Send message").performClick()

        assert(sent == "/tmp is full") {
            "an edit must resend on the first press, not be held for an unknown-command warning"
        }
    }

    private fun show(
        onSend: (String) -> Boolean = { true },
        onCommand: (SlashCommand) -> Unit = {},
        editing: String? = null,
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Composer(
                    conversationKey = null,
                    enabled = true,
                    isGenerating = false,
                    staged = emptyList<MessagePart.File>(),
                    document = null as StagedDocument?,
                    onRemoveDocument = {},
                    isAttaching = false,
                    canDictate = false,
                    isListening = false,
                    heard = "",
                    onAttach = {},
                    onRemoveStaged = {},
                    onDictate = {},
                    onSend = onSend,
                    editing = editing,
                    onStop = {},
                    onCommand = onCommand,
                )
            }
        }
    }
}

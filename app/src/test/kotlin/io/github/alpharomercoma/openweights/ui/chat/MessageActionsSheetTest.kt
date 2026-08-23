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
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How a reply is reported, and the reason this sheet matters more than it looks.
 *
 * The policy-required flagging control lives at the end of this menu. `ReportSheetTest`
 * covers the sheet it opens; nothing covered the route to it, and a control that cannot be
 * reached is the same as a control that does not exist as far as a reviewer is concerned.
 *
 * The measurements are here too, which is the other half. Tokens per second and time to
 * first token are what the listing leads on, and this is where a curious user finds them for
 * a particular reply rather than as a running total.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class MessageActionsSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `reporting a reply is reachable from the reply itself`() {
        var reported = false
        showSheet(onReport = { reported = true })

        compose.onNodeWithText("Report this reply").performClick()

        assert(reported) { "the report action must open the report sheet" }
    }

    @Test
    fun `a reply can be regenerated when there is something to regenerate`() {
        var regenerated = false
        showSheet(canRegenerate = true, onRegenerate = { regenerated = true })

        compose.onNodeWithText("Regenerate reply").performClick()

        assert(regenerated) { "regenerate must reach the view model" }
    }

    @Test
    fun `regenerating is not offered when it would do nothing`() {
        // Only the last reply can be regenerated: doing it to an earlier one would rewrite a
        // conversation the user has since built on.
        showSheet(canRegenerate = false)

        compose.onNodeWithText("Regenerate reply").assertDoesNotExist()
    }

    @Test
    fun `reading aloud offers to stop once it has started`() {
        // One control with two meanings, and getting it the wrong way round leaves somebody
        // tapping "read aloud" to silence a phone that is already talking.
        showSheet(isSpeaking = true)

        compose.onNodeWithText("Stop reading").assertIsDisplayed()
        compose.onNodeWithText("Read aloud").assertDoesNotExist()
    }

    @Test
    fun `the measurements for this reply are shown`() {
        showSheet()

        compose.onNodeWithText("13.8 tok/s", substring = true).assertIsDisplayed()
        compose.onNodeWithText("to first token", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a question can be asked again, changed`() {
        // The affordance every chat app has and this one had only in the view model: the
        // method existed with no call site, so it was not a feature, it was code.
        var edited = false
        showSheet(role = ChatRole.USER, canEdit = true, onEdit = { edited = true })

        compose.onNodeWithText("Edit and resend").performClick()

        assert(edited) { "editing must reach the caller" }
    }

    @Test
    fun `a reply cannot be edited, only a question can`() {
        // Editing rewrites what was asked. There is nothing to rewrite on the model's side,
        // and the action for a reply people want is Regenerate, which is already here.
        showSheet(role = ChatRole.ASSISTANT, canEdit = false)

        compose.onNodeWithText("Edit and resend").assertDoesNotExist()
    }

    @Test
    fun `a conversation can be branched from a turn`() {
        var branched = false
        showSheet(canBranch = true, onBranch = { branched = true })

        compose.onNodeWithText("Branch from here").performClick()

        assert(branched) { "branching must reach the caller" }
    }

    @Test
    fun `every action still fits on the smallest screen`() {
        // This column is deliberately not scrollable, and was sized when it held four rows.
        // It now holds six, and a sheet that hands the leftover space to its content drops
        // the last row off the bottom with nothing on screen to say it is there.
        showSheet(canRegenerate = true, canEdit = true, canBranch = true)

        compose.onNodeWithText("Copy text").assertIsDisplayed()
        compose.onNodeWithText("Report this reply").assertIsDisplayed()
    }

    @Test
    fun `neither is offered while the model is answering`() {
        // Both change the transcript under a turn that is still writing into it.
        showSheet(canEdit = false, canBranch = false)

        compose.onNodeWithText("Edit and resend").assertDoesNotExist()
        compose.onNodeWithText("Branch from here").assertDoesNotExist()
    }

    @Suppress("LongParameterList")
    private fun showSheet(
        canRegenerate: Boolean = true,
        isSpeaking: Boolean = false,
        onRegenerate: () -> Unit = {},
        onReport: () -> Unit = {},
        role: ChatRole = ChatRole.ASSISTANT,
        canEdit: Boolean = false,
        canBranch: Boolean = false,
        onEdit: () -> Unit = {},
        onBranch: () -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                MessageActionsSheet(
                    entry = TranscriptEntry(
                        id = 1,
                        role = role,
                        text = "A KV cache stores past attention tensors.",
                        tokensPerSecond = 13.8,
                        timeToFirstTokenMs = 412,
                        generatedTokens = 61,
                        totalMillis = 4_800,
                    ),
                    canRegenerate = canRegenerate,
                    canEdit = canEdit,
                    canBranch = canBranch,
                    isSpeaking = isSpeaking,
                    onRegenerate = onRegenerate,
                    onToggleReadAloud = {},
                    onEdit = onEdit,
                    onBranch = onBranch,
                    onReport = onReport,
                    onDismiss = {},
                )
            }
        }
    }
}

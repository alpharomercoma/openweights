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
import io.github.alpharomercoma.openweights.core.common.model.ReplyConfidence
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
    fun `the three figures a person waited for lead the panel`() {
        showSheet()

        compose.onNodeWithText("to first token").assertIsDisplayed()
        compose.onNodeWithText("in total").assertIsDisplayed()
        // The prompt as tokenized this turn plus what was written, which is the whole of
        // what the model handled: 96 generated on a 154-token prompt.
        compose.onNodeWithText("250").assertIsDisplayed()
    }

    @Test
    fun `each phase says what it read, how long it took, and how fast that was`() {
        // The failure this replaces was a run-on line of five middot-separated numbers in
        // which nothing said which rate belonged to which half of the turn.
        showSheet(prefillTokensPerSecond = 142.0)

        compose.onNodeWithText("Prefill").assertIsDisplayed()
        compose.onNodeWithText("Decode").assertIsDisplayed()
        compose.onNodeWithText("142 tok/s").assertIsDisplayed()
        compose.onNodeWithText("14 tok/s").assertIsDisplayed()
    }

    @Test
    fun `prefill counts what was read, not what the cache already held`() {
        // 154 tokens of prompt with 100 of them answered by the cache is 54 tokens of
        // actual work, and 54 is the number the prefill rate was computed against. Pairing
        // the rate with the whole prompt would make the row fail its own arithmetic.
        showSheet(prefillTokensPerSecond = 142.0)

        compose.onNodeWithText("54 tokens").assertIsDisplayed()
        compose.onNodeWithText("96 tokens").assertIsDisplayed()
    }

    @Test
    fun `a reply from before prefill was measured says so rather than inventing a number`() {
        showSheet(prefillTokensPerSecond = null)

        compose.onNodeWithText("Prefill").assertIsDisplayed()
        compose.onNodeWithText("14 tok/s").assertIsDisplayed()
    }

    @Test
    fun `a reply nobody measured offers no uncertainty view`() {
        // The view is off by default, so for most replies this action would open a sheet
        // whose whole content is an explanation of why it is empty.
        showSheet()

        compose.onNodeWithText("Where it was unsure").assertDoesNotExist()
    }

    @Test
    fun `a measured reply can be opened to see where it hesitated`() {
        var opened = false
        showSheet(
            confidence = ReplyConfidence.of(
                texts = listOf("Paris", " is", " the", " capital"),
                logprobs = listOf(-2.5f, 0f, 0f, 0f),
            ),
            onShowUncertainty = { opened = true },
        )

        compose.onNodeWithText("Where it was unsure").performClick()

        assert(opened) { "the uncertainty view must be reachable from the reply" }
    }

    @Test
    fun `perplexity joins the figures once there is one`() {
        showSheet(
            confidence = ReplyConfidence.of(
                texts = listOf("a", "b", "c", "d"),
                // Four tokens at one half each: an effective branching factor of two.
                logprobs = List(4) { -0.6931472f },
            ),
        )

        compose.onNodeWithText("perplexity").assertIsDisplayed()
        compose.onNodeWithText("2.00").assertIsDisplayed()
    }

    @Test
    fun `nothing is claimed about a reply still being written`() {
        showSheet(isStreaming = true)

        compose.onNodeWithText("Prefill").assertDoesNotExist()
        compose.onNodeWithText("in total").assertDoesNotExist()
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
        compose.onNodeWithText("Copy text as Markdown").assertIsDisplayed()
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
        prefillTokensPerSecond: Double? = null,
        isStreaming: Boolean = false,
        confidence: ReplyConfidence = ReplyConfidence.NONE,
        onShowUncertainty: () -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                MessageActionsSheet(
                    entry = TranscriptEntry(
                        id = 1,
                        role = role,
                        text = "A KV cache stores past attention tensors.",
                        tokensPerSecond = 13.8,
                        prefillTokensPerSecond = prefillTokensPerSecond,
                        timeToFirstTokenMs = 412,
                        generatedTokens = 96,
                        promptTokens = 154,
                        cachedTokens = 100,
                        prefillMs = 380,
                        decodeMs = 6_900,
                        totalMillis = 7_600,
                        isStreaming = isStreaming,
                        confidence = confidence,
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
                    onShowUncertainty = onShowUncertainty,
                    onDismiss = {},
                )
            }
        }
    }
}

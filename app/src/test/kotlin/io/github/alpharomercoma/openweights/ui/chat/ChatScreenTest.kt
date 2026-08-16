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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import io.github.alpharomercoma.openweights.core.common.context.TaskStep
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.tools.UserQuestion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * These ran on a device and therefore hardly ran at all.
 *
 * A phone is plugged in for an afternoon every few weeks, and the one this project uses is
 * usually locked, which is its own failure: an activity cannot come to the front behind a
 * keyguard, so the whole file failed with "no compose hierarchies found" rather than
 * telling anyone what was wrong. Meanwhile `verify` was green on every commit and the chat
 * screen sat at nought per cent, so a composable could gain a parameter or lose a
 * behaviour and nothing said so until somebody went looking.
 *
 * Robolectric renders the real composables on the host, which is what `PlayScreenshots`
 * already relies on. Nothing about the tests themselves changed: same rule, same
 * assertions, same screen. They just run now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reasoningIsHiddenUntilAsked() {
        // A reasoning model emits far more thinking than answer; showing it by default
        // pushes the reply off the screen.
        showChat(
            transcript = listOf(
                assistantEntry("<think>Working through the definition.</think>It caches keys."),
            ),
        )

        compose.onNodeWithText("It caches keys.").assertIsDisplayed()
        compose.onNodeWithText("Working through the definition.").assertDoesNotExist()

        compose.onNodeWithContentDescription("Show reasoning").performClick()
        compose.onNodeWithText("Working through the definition.").assertIsDisplayed()
    }

    @Test
    fun showsThinkingWhileTheReasoningBlockIsStillOpen() {
        showChat(
            transcript = listOf(
                assistantEntry("<think>Half a thought").copy(isStreaming = true),
            ),
        )

        compose.onNodeWithText("Thinking…").assertIsDisplayed()
        // The unfinished thought must not be mistaken for the answer.
        compose.onNodeWithText("Half a thought").assertDoesNotExist()
    }

    @Test
    fun reportsMeasuredThroughputOnCompletedReplies() {
        showChat(transcript = listOf(assistantEntry("Done.")))

        compose.onNodeWithText("16.4 tok/s", substring = true).assertIsDisplayed()
    }

    @Test
    fun sendIsBlockedUntilThereIsSomethingToSend() {
        var sent: String? = null
        showChat(
            transcript = emptyList(),
            onSend = {
                sent = it
                true
            },
        )

        compose.onNodeWithContentDescription("Send message").performClick()
        assert(sent == null) { "an empty composer must not send" }

        compose.onNodeWithText("Message").performTextInput("hello")
        compose.onNodeWithContentDescription("Send message").performClick()
        assert(sent == "hello") { "expected the typed text to be sent, got $sent" }
    }

    @Test
    fun offersStopWhileGenerating() {
        var stopped = false
        showChat(
            transcript = listOf(assistantEntry("partial").copy(isStreaming = true)),
            isGenerating = true,
            onStop = { stopped = true },
        )

        compose.onNodeWithContentDescription("Stop generating").performClick()
        assert(stopped) { "the stop button must reach the view model" }
    }

    /**
     * The question the model asks when a request could mean two things.
     *
     * Driven here rather than through a turn, deliberately. `ask_user` only reaches the screen
     * when a 1B model chooses to call it, which is not a thing a test can rely on, and what is
     * worth proving is the half that is ours: that the card renders, that a chip answers it,
     * and that the answer leaves the screen. Whether the model asks is measured by the
     * benchmark; whether the app can be answered is this.
     */
    @Test
    fun aQuestionFromTheModelCanBeAnsweredByTapping() {
        var answered: String? = null
        showChat(
            transcript = emptyList(),
            question = UserQuestion(
                text = "Which folder did you mean?",
                options = listOf("Notes", "Documents"),
            ),
            onAnswerQuestion = { answered = it },
        )

        compose.onNodeWithText("Which folder did you mean?").assertIsDisplayed()
        compose.onNodeWithText("Notes").performClick()

        assert(answered == "Notes") { "the tapped option must reach the model, got $answered" }
    }

    @Test
    fun aQuestionCanBeAnsweredInWordsWhenTheOptionsDoNotFit() {
        // The text box is always there, and this is why: a model that offers no options, or
        // offers them wrong, still has to be answerable.
        var answered: String? = null
        showChat(
            transcript = emptyList(),
            question = UserQuestion(text = "Which one did you mean?"),
            onAnswerQuestion = { answered = it },
        )

        compose.onNodeWithContentDescription("Answer").performTextInput("the shared one")
        compose.onNodeWithText("Answer").performClick()

        assert(answered == "the shared one") { "typed answers must reach the model, got $answered" }
    }

    @Test
    fun aPlanShowsItsStepsAndTicksTheOneThatWasTapped() {
        var ticked: Int? = null
        showChat(
            transcript = emptyList(),
            plan = TaskPlan(
                listOf(TaskStep("Find the notes"), TaskStep("Summarise them")),
            ),
            onTickStep = { ticked = it },
        )

        compose.onNodeWithText("Find the notes").assertIsDisplayed()
        compose.onNodeWithText("Summarise them").assertIsDisplayed()
        compose.onNodeWithText("Find the notes").performClick()

        assert(ticked == 0) { "ticking a step must reach the board, got $ticked" }
    }

    @Suppress("LongParameterList")
    private fun showChat(
        transcript: List<TranscriptEntry>,
        isGenerating: Boolean = false,
        onSend: (String) -> Boolean = { true },
        onStop: () -> Unit = {},
        plan: TaskPlan? = null,
        onTickStep: (Int) -> Unit = {},
        question: UserQuestion? = null,
        onAnswerQuestion: (String) -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ChatScreen(
                    state = ChatUiState(
                        modelName = "LFM2.5-2.6B-Q4_K_M",
                        modelQuantization = "lfm2 2.6B Q4_K - Medium",
                        transcript = transcript,
                        isGenerating = isGenerating,
                        contextUsed = 128,
                        contextSize = 2048,
                    ),
                    onSend = onSend,
                    onStop = onStop,
                    onRegenerate = {},
                    onNewChat = {},
                    onCompact = {},
                    plan = plan,
                    onTickStep = onTickStep,
                    question = question,
                    onAnswerQuestion = onAnswerQuestion,
                )
            }
        }
    }

    private fun assistantEntry(raw: String): TranscriptEntry {
        val parsed =
            io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply(raw)
        return TranscriptEntry(
            id = 1,
            role = ChatRole.ASSISTANT,
            text = raw,
            reasoning = parsed.reasoning,
            answer = parsed.answer,
            isReasoningInProgress = parsed.isReasoningInProgress,
            reasoningMs = 1400,
            tokensPerSecond = 16.4,
            timeToFirstTokenMs = 274,
            generatedTokens = 38,
        )
    }
}

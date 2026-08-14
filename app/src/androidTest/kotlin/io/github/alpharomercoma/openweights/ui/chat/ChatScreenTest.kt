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
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test

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

    private fun showChat(
        transcript: List<TranscriptEntry>,
        isGenerating: Boolean = false,
        onSend: (String) -> Boolean = { true },
        onStop: () -> Unit = {},
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

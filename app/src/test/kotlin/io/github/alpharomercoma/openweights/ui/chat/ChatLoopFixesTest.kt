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

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Three small things the loop got wrong, each found by the 2026-09-02 sweep and each
 * about the boundary between this screen and the rest of the app: what Stop may cancel,
 * what returning to the tab may load, and what a turn that ended on a call leaves behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatLoopFixesTest : ChatFixture() {

    @Test
    fun `stop with no turn running leaves the runtime alone`() = runTest(dispatcher) {
        // The runtime is shared with the watches and the goal. A reopen or a delete calls
        // stop() to end this chat's turn, and with none running it used to cancel
        // whichever of those had the engine at that moment.
        loadModel()
        val before = engine.cancelCount

        viewModel.stop()
        settle()

        assertThat(engine.cancelCount).isEqualTo(before)
    }

    @Test
    fun `a model the user unloaded is not loaded back by the tab`() = runTest(dispatcher) {
        loadModel()
        assertThat(viewModel.uiState.value.modelName).isNotNull()

        viewModel.unloadModel()
        settleUntil { viewModel.uiState.value.modelName == null }
        // What the chat tab does whenever it finds no model loaded.
        viewModel.loadDefaultModel()
        settle(steps = FOLD_SETTLE_STEPS)

        assertThat(viewModel.uiState.value.modelName).isNull()
        assertThat(viewModel.uiState.value.isLoadingModel).isFalse()

        // A pick is a choice again.
        loadModel()
        assertThat(viewModel.uiState.value.modelName).isNotNull()
    }

    @Test
    fun `a turn that ends on a call nobody ran keeps a reply the conversation can store`() =
        runTest(dispatcher) {
            // Every pass asks for a tool and never writes prose. The round cap withdraws the
            // third, the fourth asks anyway, and the turn ends on it: a reply with steps
            // under it and no text, which the screen showed and storage never received.
            engine.supportsTools = true
            loadModel()
            repeat(UNANSWERED_PASSES) { index ->
                engine.scripted += ScriptedPass(
                    text = "",
                    toolCalls = listOf(
                        ToolCall(
                            id = "$index",
                            name = "web_search",
                            argumentsJson = """{"query":"x$index"}""",
                        ),
                    ),
                )
            }

            viewModel.send("Who is Ada Lovelace?")
            settle(steps = FOLD_SETTLE_STEPS)

            val shown = viewModel.uiState.value.transcript.last()
            assertThat(shown.role).isEqualTo(ChatRole.ASSISTANT)
            assertThat(shown.isStreaming).isFalse()
            assertThat(shown.text).contains("web_search")
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)
            val stored = chats.messages(id).last()
            assertThat(stored.role).isEqualTo(ChatRole.ASSISTANT.wireName)
            assertThat(stored.text).isEqualTo(shown.text)
        }

    @Test
    fun `a full window lets the tool observations go before it writes a summary`() =
        runTest(dispatcher) {
            // The engine's record replays every tool result verbatim; when those are what
            // filled the window, dropping the record is a re-read, and a fold is a re-read
            // plus a summary the model has to write.
            engine.supportsTools = true
            engine.countsPrompt = true
            loadModel()
            // Enough turns for the policy to have something to fold at all.
            repeat(EARLIER_TURNS) { index ->
                engine.scripted += ScriptedPass("Answer $index.")
                viewModel.send("Question $index")
                settle()
            }
            // Then two turns that each bring a page in. Two, because a turn's tool budget
            // is half of what is free, so no single page can fill the window on its own;
            // the second, sized to what the first left, takes the reading past the line.
            StubTool.answer = "word ".repeat(PAGE_WORDS)
            try {
                repeat(PAGE_TURNS) { index ->
                    val call = ToolCall(
                        id = "$index",
                        name = "web_search",
                        argumentsJson = """{"query":"x$index"}""",
                    )
                    engine.scripted += ScriptedPass(text = "Looking.", toolCalls = listOf(call))
                    engine.scripted += ScriptedPass("Ada Lovelace wrote the first algorithm.")
                    viewModel.send("Who is Ada Lovelace, part $index?")
                    settle(steps = FOLD_SETTLE_STEPS)
                }
            } finally {
                StubTool.answer = StubTool.DEFAULT_ANSWER
            }

            // The pages did fill it, the record went, and no summary was written for it.
            val state = viewModel.uiState.value
            assertThat(engine.contextUsed * FULL_DENOMINATOR)
                .isAtLeast(state.contextSize * FULL_NUMERATOR)
            assertThat(state.engineHistory).isNull()
            assertThat(state.compaction).isNull()
            val summaries = engine.prompts.count { messages ->
                messages.any { it.text.startsWith("Summarize") }
            }
            assertThat(summaries).isEqualTo(0)
        }

    private companion object {
        const val UNANSWERED_PASSES = 4
        const val EARLIER_TURNS = 2
        const val PAGE_TURNS = 2

        /** Ten thousand characters: more than any one turn's budget, so each is cut to fit. */
        const val PAGE_WORDS = 2_000

        /** The policy's line: three quarters of the window. */
        const val FULL_NUMERATOR = 3
        const val FULL_DENOMINATOR = 4
    }
}

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
import io.github.alpharomercoma.openweights.core.common.context.Compaction
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import org.junit.Test

class EngineMessagesTest {
    @Test
    fun `a model whose template cannot render tools is not told it has any`() {
        val state = ChatUiState(
            transcript = transcript(1),
            mode = AgentMode.AUTO,
            supportsTools = false,
        )

        val system = state.engineMessages().single { it.role == ChatRole.SYSTEM }

        // The regression this exists for: the instruction went in unconditionally, so a
        // 1.5B audio model with no tool support was told it had tools, could not emit a
        // call, and answered "hello" with "I do not have a tool for that". How long an
        // answer should be is a separate matter and is always said.
        assertThat(system.text).doesNotContain("LOOKUP:")
        assertThat(system.text).contains("Answer the question directly")
    }

    @Test
    fun `a model that can call tools is told how to`() {
        val state = ChatUiState(
            transcript = transcript(1),
            mode = AgentMode.AUTO,
            supportsTools = true,
            toolsAvailable = true,
        )

        val system = state.engineMessages().single { it.role == ChatRole.SYSTEM }

        assertThat(system.text).contains("LOOKUP:")
    }

    @Test
    fun `a model with every tool switched off is not told it can look things up`() {
        val state = ChatUiState(
            transcript = transcript(1),
            mode = AgentMode.AUTO,
            supportsTools = true,
            toolsAvailable = false,
        )

        val system = state.engineMessages().single { it.role == ChatRole.SYSTEM }

        // Being able to render a call and having one to make are different things. Told it
        // could search with search switched off, the model answered by saying it would.
        assertThat(system.text).doesNotContain("LOOKUP:")
    }

    @Test
    fun `without compaction every turn is sent`() {
        val state = ChatUiState(transcript = transcript(4))

        // Four turns, and the one instruction every conversation opens with: how long an
        // answer should be. Tools are not in it, because this state has none.
        assertThat(state.engineMessages()).hasSize(5)
    }

    @Test
    fun `the summary text actually reaches the model`() {
        // Regression: the summary was previously built with an escaped template expression,
        // so the model received the literal placeholder and lost every folded turn.
        val state = ChatUiState(
            transcript = transcript(6),
            compaction = Compaction(
                summary = "The user is porting a parser.",
                foldedThroughIndex = 3,
                foldedEntryCount = 4,
            ),
            supportsTools = true,
            toolsAvailable = true,
        )

        val messages = state.engineMessages()

        // The instruction turn comes first, then the summary as its own system turn. Its
        // two halves are both load-bearing: answer from memory, and the one line to write
        // instead when memory is not enough.
        assertThat(messages.first().text).contains("Answer from what you already know")
        assertThat(messages.first().text).contains("LOOKUP:")
        val summary = messages[1]
        assertThat(summary.role).isEqualTo(ChatRole.SYSTEM)
        assertThat(summary.text).contains("The user is porting a parser.")
        assertThat(summary.text).doesNotContain("$")
    }

    @Test
    fun `folded turns are replaced rather than duplicated`() {
        val state = ChatUiState(
            transcript = transcript(6),
            compaction = Compaction("summary", foldedThroughIndex = 3, foldedEntryCount = 4),
            supportsTools = true,
            toolsAvailable = true,
        )

        val messages = state.engineMessages()

        // One summary plus the two turns after the fold.
        assertThat(messages).hasSize(4)
        assertThat(messages.drop(2).map { it.text }).containsExactly("turn 4", "turn 5").inOrder()
    }

    private fun transcript(count: Int) = List(count) { index ->
        TranscriptEntry(
            id = index.toLong(),
            role = if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
            text = "turn $index",
        )
    }
}

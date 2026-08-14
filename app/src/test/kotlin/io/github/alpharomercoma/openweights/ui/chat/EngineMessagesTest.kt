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
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
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
        assertThat(system.text).doesNotContain("Search only when")
        assertThat(system.text).contains("Answer from what you know")
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

        assertThat(system.text).contains("Search only when")
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
        assertThat(system.text).doesNotContain("Search only when")
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

        // One system turn carrying all of it. The summary used to be a second one, which
        // is what templates requiring strict alternation refuse. Every part is
        // load-bearing: answer from memory, when to go and look, and what was folded away.
        val system = messages.single { it.role == ChatRole.SYSTEM }
        assertThat(system.text).contains("Answer from what you know")
        assertThat(system.text).contains("Search only when")
        assertThat(system.text).contains("The user is porting a parser.")
        assertThat(system.text).doesNotContain("$")
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

        // The instructions, which now carry the summary, plus the two turns after the fold.
        assertThat(messages).hasSize(3)
        assertThat(messages.drop(1).map { it.text }).containsExactly("turn 4", "turn 5").inOrder()
    }

    @Test
    fun `a folded conversation is still one system turn, then strict alternation`() {
        val state = ChatUiState(
            transcript = transcript(6),
            compaction = Compaction("summary", foldedThroughIndex = 3, foldedEntryCount = 4),
            supportsTools = true,
            toolsAvailable = true,
        )

        val messages = state.engineMessages()

        // Gemma 3 raises "Conversation roles must alternate user/assistant" and refuses to
        // render the prompt at all, so every turn after the first fold failed and the
        // conversation could not be continued. Found on a device: the summary went in as a
        // second system turn beside the instructions.
        assertThat(messages.count { it.role == ChatRole.SYSTEM }).isEqualTo(1)
        assertThat(messages.first().role).isEqualTo(ChatRole.SYSTEM)
        assertThat(messages.first().text).contains("summary")
        assertThat(messages.first().text).contains("Answer from what you know")
        assertAlternates(messages)
    }

    @Test
    fun `two questions in a row are sent as one turn`() {
        // What a stop before the first token leaves behind: the empty reply is dropped
        // rather than written down, so the next question follows the previous one with no
        // answer between them. The same templates refuse that.
        val state = ChatUiState(
            transcript = listOf(
                TranscriptEntry(id = 0, role = ChatRole.USER, text = "first"),
                TranscriptEntry(id = 1, role = ChatRole.USER, text = "second"),
            ),
        )

        val messages = state.engineMessages()

        assertAlternates(messages)
        assertThat(messages.last().text).contains("first")
        assertThat(messages.last().text).contains("second")
    }

    @Test
    fun `a fold that lands mid exchange does not open on an answer`() {
        // foldRange keeps a fixed number of recent entries, and nothing makes that boundary
        // land on a question. Opening on an answer is the same violation from the other end.
        val state = ChatUiState(
            transcript = transcript(6),
            compaction = Compaction("summary", foldedThroughIndex = 2, foldedEntryCount = 3),
        )

        assertAlternates(state.engineMessages())
    }

    /**
     * Fails unless the prompt is one leading system turn and then user, assistant, user.
     *
     * The shape several widely used templates require rather than prefer. Gemma 3 raises a
     * Jinja exception and renders nothing at all, which arrives as a red error in place of
     * the answer and repeats on every turn afterwards.
     */
    private fun assertAlternates(messages: List<ChatMessage>) {
        val body = messages.dropWhile { it.role == ChatRole.SYSTEM }
        assertThat(body.map { it.role }).doesNotContain(ChatRole.SYSTEM)
        assertThat(body.first().role).isEqualTo(ChatRole.USER)
        body.zipWithNext().forEach { (before, after) ->
            assertThat(before.role).isNotEqualTo(after.role)
        }
    }

    private fun transcript(count: Int) = List(count) { index ->
        TranscriptEntry(
            id = index.toLong(),
            role = if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
            text = "turn $index",
        )
    }
}

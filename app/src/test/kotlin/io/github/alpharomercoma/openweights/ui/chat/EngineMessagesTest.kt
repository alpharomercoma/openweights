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
import org.junit.Test

class EngineMessagesTest {
    @Test
    fun `without compaction every turn is sent`() {
        val state = ChatUiState(transcript = transcript(4))

        // One more than the turns: every conversation opens with the tool instruction.
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
        )

        val messages = state.engineMessages()

        // The instruction turn comes first, then the summary as its own system turn.
        assertThat(messages.first().text).contains("You have tools")
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

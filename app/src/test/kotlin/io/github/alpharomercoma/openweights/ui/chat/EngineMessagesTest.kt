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
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.engine.GenerationStats
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.ToolNotes
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

        // The wording that measured best states a default rather than a rule with two
        // halves. It used to name web_search and say when to prefer it, and the model was
        // measured reading both halves back to itself and choosing the wrong one: it
        // searched for the author of Pride and Prejudice after stating the answer
        // correctly. So the pin moved off the tool's name and onto the clause that
        // replaced it, which is the part that has to survive an edit.
        assertThat(system.text).contains("Do not search to double check")
        // The date deliberately stays OUT of the instructions — it is the one line that
        // changes daily, and in the head it invalidated the warm snapshot and disk store
        // at every midnight. It rides on the first user turn instead.
        assertThat(system.text).doesNotContain("Today is")
        val firstUser = state.engineMessages().first { it.role == ChatRole.USER }
        assertThat(firstUser.text).contains("Today is")
    }

    /**
     * A research step's own turn against the configured default: the reported failure was
     * the model answering "I don't have the current information stored" instead of
     * searching, because the default tells it not to bother.
     */
    @Test
    fun `an override replaces the configured tool prompt rather than adding to it`() {
        val state = ChatUiState(
            transcript = transcript(1),
            mode = AgentMode.AUTO,
            supportsTools = true,
            toolsAvailable = true,
        )

        val system = state.engineMessages(toolPromptOverride = "Search first, always.")
            .single { it.role == ChatRole.SYSTEM }

        assertThat(system.text).contains("Search first, always.")
        assertThat(system.text).doesNotContain("Do not search to double check")
    }

    @Test
    fun `with no override the configured tool prompt is unchanged`() {
        val state = ChatUiState(
            transcript = transcript(1),
            mode = AgentMode.AUTO,
            supportsTools = true,
            toolsAvailable = true,
        )

        val system = state.engineMessages().single { it.role == ChatRole.SYSTEM }

        assertThat(system.text).contains("Do not search to double check")
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

        // One system turn for the instructions, and the summary as a turn of the
        // conversation rather than part of them. In the system message the model answered
        // 4 of 7 questions the summary covered and reached for a tool on all 7; as a turn it
        // answered 6 and reached for a tool on 3. See `recap`.
        val system = messages.single { it.role == ChatRole.SYSTEM }
        assertThat(system.text).contains("Answer from what you know")
        assertThat(system.text).contains("Do not search to double check")
        assertThat(system.text).doesNotContain("The user is porting a parser.")
        assertThat(system.text).doesNotContain("$")
        // Post-fold the recap is the conversation's first user turn, so it also carries
        // the date — the one line kept out of the instructions so the head stays
        // byte-stable across midnight.
        val recap = messages.first { it.text.contains("Earlier in this conversation:") }
        assertThat(recap.text).contains("The user is porting a parser.")
        assertThat(recap.text).contains("Today is")
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

        // The instructions, the recap exchange that stands in for what was folded, and the
        // two turns after the fold. The folded turns themselves appear nowhere.
        assertThat(messages).hasSize(5)
        assertThat(messages.drop(3).map { it.text }).containsExactly("turn 4", "turn 5").inOrder()
        assertThat(messages.map { it.text }.none { it.contains("turn 0") }).isTrue()
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
        assertThat(messages.first().text).contains("Answer from what you know")
        // The summary is a turn now, and the alternation it has to keep is the whole reason
        // it is a user turn followed by an assistant one rather than a system turn of its own.
        assertThat(messages[1].text).contains("summary")
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

    @Test
    fun `a transcript with no question in it is not sent at all`() {
        // What a fold leaves when everything it kept is an answer: the opening assistant
        // turns are dropped, and nothing is left to answer. Sending the instructions on
        // their own asks the model to reply to nobody, which some templates refuse and the
        // rest answer at random.
        val state = ChatUiState(
            transcript = listOf(
                TranscriptEntry(id = 0, role = ChatRole.ASSISTANT, text = "an answer"),
                TranscriptEntry(id = 1, role = ChatRole.ASSISTANT, text = "another"),
            ),
        )

        assertThat(state.engineMessages()).isEmpty()
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

    @Test
    fun `a mode that is not the default says so on screen`() {
        // Choosing a mode changed the prompt and nothing else. Nowhere in the app said which
        // one was on, so the only way to find out you were in plan mode was to notice tools
        // not running. Auto stays unlabelled: it is the default, and a line that always says
        // the same thing says nothing.
        val identity = { mode: AgentMode ->
            ChatUiState(backend = "CPU", contextSize = 4096, mode = mode).runtimeIdentity
        }

        assertThat(identity(AgentMode.PLAN)).contains(AgentMode.PLAN.label)
        assertThat(identity(AgentMode.ASK)).contains(AgentMode.ASK.label)
        assertThat(identity(AgentMode.AUTO)).doesNotContain(AgentMode.AUTO.label)
    }

    @Test
    fun `plan mode still says something when every tool is switched off`() {
        // The instruction used to be gated on a tool being available, so a user who had
        // switched them all off and typed /plan got a mode that changed nothing: no
        // instruction, and nothing on screen to say so either.
        val planning = ChatUiState(
            transcript = transcript(1),
            mode = AgentMode.PLAN,
            toolsAvailable = false,
        )

        val system = planning.engineMessages().first { it.role == ChatRole.SYSTEM }.text

        assertThat(system).contains("Say what you would do")
        // And it does not claim tools it has not got, which is what the other wording says.
        assertThat(system).doesNotContain("You have tools available")
    }

    @Test
    fun `the prompt estimate follows the ratio the model was measured at`() {
        val state = ChatUiState(transcript = transcript(6))
        val chars = state.engineMessages().sumOf { it.text.length }

        // Four characters to a token, which is roughly what English costs, against the two
        // this used to borrow from the attachment budget. The difference is a conversation
        // read as twice as full as it is, and a fold that fires at half the size it should.
        val measured = state.copy(charsPerToken = 4f).estimatedPromptTokens()

        assertThat(measured).isEqualTo(chars / 4)
        assertThat(state.estimatedPromptTokens()).isEqualTo(chars / 2)
    }

    @Test
    fun `a pass that measured nothing leaves the ratio alone`() {
        // contextUsed is zero before anything has been decoded, and dividing by it would
        // put an infinity into the state that every later estimate would inherit.
        val stats = GenerationStats(
            promptTokens = 0,
            generatedTokens = 0,
            prefillMs = 0,
            decodeMs = 0,
            timeToFirstTokenMs = 0,
            contextUsed = 0,
            contextSize = 4096,
        )

        assertThat(stats.charsPerToken(chars = 400)).isNull()
    }

    @Test
    fun `session tokens are null before anything has been generated`() {
        // A fresh conversation has nothing to report, and showing zeroes would read as a
        // real, measured "nothing happened" rather than as "nothing to measure yet".
        val state = ChatUiState(transcript = transcript(1))

        assertThat(state.sessionTokens()).isNull()
    }

    @Test
    fun `session tokens sum every measured assistant turn`() {
        val state = ChatUiState(
            transcript = listOf(
                TranscriptEntry(
                    id = 1,
                    role = ChatRole.USER,
                    text = "hello",
                ),
                TranscriptEntry(
                    id = 2,
                    role = ChatRole.ASSISTANT,
                    text = "hi",
                    generatedTokens = 40,
                    promptTokens = 500,
                    cachedTokens = 400,
                ),
                TranscriptEntry(
                    id = 3,
                    role = ChatRole.USER,
                    text = "and then?",
                ),
                TranscriptEntry(
                    id = 4,
                    role = ChatRole.ASSISTANT,
                    text = "then this",
                    generatedTokens = 60,
                    // A follow-up turn's prompt tokenizes to the whole conversation again,
                    // so this is not the delta on top of turn one — it is turn two's own
                    // full (cached + fresh) count.
                    promptTokens = 560,
                    cachedTokens = 540,
                ),
            ),
        )

        val session = state.sessionTokens()

        assertThat(session?.inputTokens).isEqualTo(1_060)
        assertThat(session?.outputTokens).isEqualTo(100)
        // 940 of 1060 reused.
        assertThat(session?.cacheHitRate).isWithin(0.0001).of(940.0 / 1060.0)
    }

    @Test
    fun `a reply reloaded from storage without measurements does not count`() {
        // A conversation reopened from disk has replies with no promptTokens on them at
        // all — the engine has not touched this conversation yet — which is a different
        // claim from a real, measured zero and has to be excluded rather than summed as one.
        val state = ChatUiState(
            transcript = listOf(
                TranscriptEntry(id = 1, role = ChatRole.USER, text = "hello"),
                TranscriptEntry(id = 2, role = ChatRole.ASSISTANT, text = "hi"),
            ),
        )

        assertThat(state.sessionTokens()).isNull()
    }

    @Test
    fun `what the tools found rides in the question, not in a turn of its own`() {
        // A turn of its own would be a second user message in a row, which is the wall the
        // compaction summary already hit: the templates that enforce alternation refuse to
        // render it rather than ignoring it.
        val state = ChatUiState(transcript = transcript(1), toolNotes = notes())

        val messages = state.engineMessages()

        assertThat(messages.count { it.role == ChatRole.USER }).isEqualTo(1)
        assertThat(messages.last().role).isEqualTo(ChatRole.USER)
        assertThat(messages.last().text).contains("Vaughan")
    }

    @Test
    fun `the question comes last, after the notes`() {
        // Whatever is nearest the end is what a small model answers. Notes in that position
        // get summarised back instead of used.
        val state = ChatUiState(transcript = transcript(1), toolNotes = notes())

        val text = state.engineMessages().last().text

        assertThat(text.indexOf("Vaughan")).isLessThan(text.indexOf("turn 0"))
    }

    @Test
    fun `the instructions do not carry them, so the cache survives a tool call`() {
        // The instructions are the root of the KV cache and the whole conversation sits
        // behind them. A record that grows with every tool call would invalidate the lot and
        // re-prefill a conversation that had not changed.
        val state = ChatUiState(transcript = transcript(1), toolNotes = notes())

        val system = state.engineMessages().single { it.role == ChatRole.SYSTEM }

        assertThat(system.text).doesNotContain("Vaughan")
    }

    @Test
    fun `a chat where no tool has run is sent exactly what it was before`() {
        val state = ChatUiState(transcript = transcript(3))

        val withoutNotes = state.engineMessages()

        assertThat(withoutNotes.last().text).isEqualTo("turn 2")
    }

    @Test
    fun `notes with nothing to attach to are dropped rather than sent alone`() {
        // A transcript whose last turn is the model's reaches this on the regenerate path.
        // Appending there would put the record in the model's mouth as though it had said it.
        val state = ChatUiState(transcript = transcript(2), toolNotes = notes())

        val messages = state.engineMessages()

        assertThat(messages.last().role).isEqualTo(ChatRole.ASSISTANT)
        assertThat(messages.none { it.text.contains("Vaughan") }).isTrue()
    }

    private fun notes() = ToolNotes().withSteps(
        listOf(
            AgentStep.Ran(
                ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"lovelace"}"""),
                "Ada Lovelace, collaborators included Vaughan and Babbage.",
                millis = 1,
            ),
        ),
    ) { null }

    private fun transcript(count: Int) = List(count) { index ->
        TranscriptEntry(
            id = index.toLong(),
            role = if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
            text = "turn $index",
        )
    }
}

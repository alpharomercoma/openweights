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

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.data.Offload
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * One turn, from send to the reply being stored.
 *
 * What stop leaves behind, what reaches the screen while tokens arrive, and what a turn that
 * called a tool is written down as. Everything either side of a turn is in
 * [ChatConversationsTest]; the harness both sit on is [ChatFixture].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelTest : ChatFixture() {
    @Test
    fun `stopping before the message is written gives the composer back`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true

        viewModel.send("Something")
        // Deliberately not settled: the row is still being written and generate() has not
        // run, so there is no generation job in the old sense of the word. Stop was
        // ignored here, and the turn it looked like it had cancelled went on to run
        // anyway, against whatever state had replaced it.
        assertThat(viewModel.uiState.value.isGenerating).isTrue()

        viewModel.stop()
        settle()

        assertThat(viewModel.uiState.value.isGenerating).isFalse()
    }

    @Test
    fun `stopping a generation leaves what was produced and clears the busy state`() =
        runTest(dispatcher) {
            loadModel()
            engine.hold = true

            viewModel.send("Tell me something long")
            settle()
            engine.emit("Partial ")
            settle()
            assertThat(viewModel.uiState.value.isGenerating).isTrue()

            viewModel.stop()
            settle()

            assertThat(viewModel.uiState.value.isGenerating).isFalse()
            assertThat(engine.cancelCount).isAtLeast(1)
            val last = viewModel.uiState.value.transcript.last()
            assertThat(last.isStreaming).isFalse()
            assertThat(last.text).contains("Partial")
        }

    @Test
    fun `stopping keeps the tokens produced since the last publish`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true
        viewModel.send("Tell me something long")
        settle()

        engine.emit("First half. ")
        settle()
        // Collected without letting real time pass, so this piece falls inside the
        // coalescing window and never reaches the screen on its own. It is the piece a
        // naive stop drops.
        engine.emit("Second half.")
        advanceUntilIdle()

        viewModel.stop()
        settle()

        val id = requireNotNull(viewModel.uiState.value.activeConversationId)
        val shown = viewModel.uiState.value.transcript.last()
        assertThat(shown.text).isEqualTo("First half. Second half.")
        // The same string on both sides. A reply the user can read but the app cannot
        // resend is worse than one that was never produced.
        val stored = awaitMessages(id, count = 2).last()
        assertThat(stored.role).isEqualTo("assistant")
        assertThat(stored.text).isEqualTo("First half. Second half.")
    }

    @Test
    fun `stopping before any token leaves no empty reply behind`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true
        viewModel.send("Never mind")
        settle()

        viewModel.stop()
        settle()

        val id = requireNotNull(viewModel.uiState.value.activeConversationId)
        assertThat(viewModel.uiState.value.transcript.map { it.role })
            .containsExactly(ChatRole.USER)
        assertThat(database.messages().forConversation(id).map { it.role })
            .containsExactly("user")
    }

    @Test
    fun `a finished reply is stored exactly as it is shown`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true
        viewModel.send("Question")
        settle()

        engine.emit("One ")
        engine.emit("two ")
        engine.emit("three.")
        engine.finish()
        settle()

        val id = requireNotNull(viewModel.uiState.value.activeConversationId)
        val shown = viewModel.uiState.value.transcript.last()
        assertThat(shown.isStreaming).isFalse()
        assertThat(shown.text).isEqualTo("One two three.")
        assertThat(awaitMessages(id, count = 2).last().text).isEqualTo("One two three.")
    }

    @Test
    fun `thinking is not repeated in the answer when no tool was called`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true
        viewModel.send("Question")
        settle()

        engine.emit("<think>Weighing the instructions.</think>")
        engine.emit("The answer.")
        // What the engine really hands back for a reply with no tool call: the whole thing,
        // thinking included, because llama.cpp only returns cleaned text once its parser has
        // recognised a call. Preferring that over the local split put the reasoning on
        // screen twice, once collapsed and once as the reply.
        engine.finish(content = "<think>Weighing the instructions.</think>The answer.")
        settle()

        val shown = viewModel.uiState.value.transcript.last()
        assertThat(shown.reasoning).isEqualTo("Weighing the instructions.")
        assertThat(shown.answer).isEqualTo("The answer.")
    }

    @Test
    fun `a model that reasons anyway loses its thinking switch`() = runTest(dispatcher) {
        loadModel()
        viewModel.savePreferences(viewModel.uiState.value.preferences.copy(thinking = false))
        settle()
        assertThat(viewModel.uiState.value.supportsThinking).isTrue()

        engine.hold = true
        viewModel.send("Question")
        settle()
        // Told not to think, and it thought. The template test at load cannot catch this,
        // because the template does branch on the flag; the weights are what ignore it.
        engine.emit("<think>Thinking anyway.</think>The answer.")
        engine.finish(content = "<think>Thinking anyway.</think>The answer.")
        settle()

        assertThat(viewModel.uiState.value.supportsThinking).isFalse()
    }

    @Test
    fun `a turn with a tool is stored as one reply, not one per pass`() = runTest(dispatcher) {
        engine.supportsTools = true
        loadModel()
        engine.scripted += ScriptedPass(
            text = "Looking that up.",
            toolCalls = listOf(
                ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"x"}"""),
            ),
        )
        engine.scripted += ScriptedPass("Ada Lovelace wrote the first algorithm.")

        viewModel.send("Who is Ada Lovelace?")
        settle(steps = FOLD_SETTLE_STEPS)

        val id = requireNotNull(viewModel.uiState.value.activeConversationId)
        val stored = database.messages().forConversation(id)

        // The screen shows one answer with the tool folded into it, and storage has to
        // agree. It used to be written once per pass, so this reopened as the interim
        // reply that asked for the tool followed by the real one, and the model was
        // resent that as history.
        assertThat(stored.map { it.role }).containsExactly("user", "assistant").inOrder()
        assertThat(stored.last().text).contains("Ada Lovelace")
        assertThat(stored.last().text).doesNotContain("Looking that up")

        // The ledger still counts both passes, which is the one part of this that was
        // already right: the phone really did decode twice to answer once, and the
        // usage tab is about work done rather than about replies kept.
        val usage = database.usage().observeAll().first().single()
        assertThat(usage.generatedTokens).isEqualTo(FAKE_TOKENS_PER_PASS * PASSES)
    }

    @Test
    fun `stopping while a tool waits for approval frees the screen`() = runTest(dispatcher) {
        engine.supportsTools = true
        viewModel.setMode(AgentMode.ASK)
        loadModel()
        engine.scripted += ScriptedPass(
            text = "Let me look.",
            toolCalls = listOf(
                ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"x"}"""),
            ),
        )

        viewModel.send("Who is Ada Lovelace?")
        settle()
        // The turn is parked on a question only the user can answer.
        assertThat(viewModel.uiState.value.pendingApproval).isNotNull()

        viewModel.stop()
        settle()

        // Nothing left holding the screen: no dialog waiting on an answer for a turn
        // that no longer exists, no row still claiming to be streaming, and a composer
        // that takes the next question.
        assertThat(viewModel.uiState.value.pendingApproval).isNull()
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        assertThat(viewModel.uiState.value.transcript.none { it.isStreaming }).isTrue()
    }

    @Test
    fun `an empty reply is said rather than shown`() = runTest(dispatcher) {
        loadModel()
        // A pass that ends immediately with no text and no call. Small models do this
        // when the template renders something they will not continue.
        engine.scripted += ScriptedPass("")

        viewModel.send("Anything")
        settle()

        // The empty placeholder is dropped, correctly, because a blank turn is worse
        // than no turn. But then nothing at all is said: the question sits on screen
        // with no answer, no error, and a composer that works again, so the only
        // reading is that the app ignored it.
        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
    }

    @Test
    fun `unreadable tool markup is not shown as the answer`() = runTest(dispatcher) {
        engine.supportsTools = true
        loadModel()
        // Call-shaped text naming a tool that does not exist, which llama.cpp's parser
        // did not recognise either, so it hands back no calls and no cleaned content.
        engine.scripted += ScriptedPass(
            text = "<|tool_call_start|>[read_my_email()]<|tool_call_end|>",
            content = "",
        )

        viewModel.send("Read my email")
        settle()

        // Salvage finds no tool of that name, so there is nothing to run and the raw
        // pass becomes the reply. What reached the screen was the markup itself.
        val shown = viewModel.uiState.value.transcript.last()
        assertThat(shown.answer).doesNotContain("tool_call_start")
        assertThat(shown.answer).doesNotContain("read_my_email")
    }

    @Test
    fun `stopping over a half written call does not write the call down`() = runTest(dispatcher) {
        engine.supportsTools = true
        loadModel()
        // Stop pressed while the model is partway through emitting a call. The completed
        // path has always stripped this; the interrupted path did not, so the markup went
        // into storage as the assistant's turn and came back as history on every later turn
        // of that conversation, an assistant asking for a tool with no result after it.
        engine.hold = true
        viewModel.send("Who won?")
        settle()
        engine.emit("Looking that up. <|tool_call_start|>[web_search(query=")
        advanceUntilIdle()

        viewModel.stop()
        settle()

        val id = requireNotNull(viewModel.uiState.value.activeConversationId)
        val shown = viewModel.uiState.value.transcript.last()
        assertThat(shown.answer).doesNotContain("tool_call_start")
        assertThat(shown.answer).doesNotContain("web_search")
        // Storage most of all: what is on screen is this turn, and what is stored is every
        // later turn's history.
        val stored = awaitMessages(id, count = 2).last()
        assertThat(stored.text).doesNotContain("tool_call_start")
    }

    @Test
    fun `a question asked before a model is loaded is refused, not swallowed`() =
        runTest(dispatcher) {
            // The cold start: the app opens, somebody types, and the weights are not mapped
            // yet. send returned nothing either way and the composer cleared regardless, so
            // the question vanished with nothing said about it.
            val accepted = viewModel.send("Too early")
            settle()

            assertThat(accepted).isFalse()
            assertThat(viewModel.uiState.value.error).isNotNull()
            assertThat(viewModel.uiState.value.transcript).isEmpty()
        }

    @Test
    fun `a question asked while the weights are still loading is refused`() = runTest(dispatcher) {
        engine.loadDelayMs = LOAD_MS
        viewModel.loadModel(modelFile("model-a.gguf"))
        advanceTimeBy(LOAD_MS / 2)

        val accepted = viewModel.send("Too early")

        // False is what the composer reads to decide whether to keep what was typed.
        assertThat(accepted).isFalse()
        advanceUntilIdle()
        settle()
        engine.loadDelayMs = 0
    }

    @Test
    fun `moving the processor reloads the weights and keeps the chat`() = runTest(dispatcher) {
        engine.hasGpu = true
        loadModel()
        viewModel.send("A question")
        settle()
        engine.finish(content = "An answer.")
        settle()
        val before = viewModel.uiState.value.transcript.size

        viewModel.savePreferences(
            viewModel.uiState.value.preferences.copy(offload = Offload.GPU.name),
        )
        settle()

        // Two loads, and the second one asks for the GPU. Saving alone used to be the whole
        // of it: the setting sat in storage until the model happened to load again, which
        // for most people is never, and the top bar went on truthfully reporting the CPU.
        assertThat(engine.loads).hasSize(2)
        assertThat(engine.loadParams.last().gpuLayers).isGreaterThan(0)
        assertThat(engine.loadParams.first().gpuLayers).isEqualTo(0)
        // The weights moved, not the conversation.
        assertThat(viewModel.uiState.value.transcript).hasSize(before)
    }

    @Test
    fun `settings that are not the processor do not reload`() = runTest(dispatcher) {
        engine.hasGpu = true
        loadModel()

        viewModel.savePreferences(viewModel.uiState.value.preferences.copy(temperature = 0.1f))
        settle()

        assertThat(engine.loads).hasSize(1)
    }

    @Test
    fun `a turn that dies mid flight leaves nothing streaming`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true
        viewModel.send("Answer me")
        settle()
        engine.emit("Half an ans")
        settle()

        // The engine's channel closing under the collector, which is what process death
        // and an engine crash both look like from here: no completion event ever arrives.
        engine.cancel()
        settle()

        // Nothing may be left claiming to be streaming, or the row spins forever and the
        // composer never comes back.
        assertThat(viewModel.uiState.value.transcript.none { it.isStreaming }).isTrue()
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
    }

    @Test
    fun `a file the model cannot read is refused rather than sent and dropped`() =
        runTest(dispatcher) {
            // No projector, so the engine would drop the attachment while the message still
            // showed it. Refusing at the composer is the only place the user can act on it.
            loadModel(name = "text-only.gguf")
            val picture = File(models, "photo.jpg").apply { writeText("not really a jpeg") }

            viewModel.attach(Uri.fromFile(picture))
            settle()

            assertThat(viewModel.uiState.value.staged).isEmpty()
            assertThat(viewModel.uiState.value.error).contains("text only")
        }
}

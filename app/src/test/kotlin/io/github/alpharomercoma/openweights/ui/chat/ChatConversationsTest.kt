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

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.data.decodeAttachments
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Everything either side of a turn.
 *
 * Opening a past conversation, deleting one, folding a long one, switching model without
 * losing the thread, and coming back after the process was killed. Each of these has been a
 * real defect: work landing in the wrong conversation is the failure this suite exists for.
 * One turn on its own is in [ChatViewModelTest]; the harness both sit on is [ChatFixture].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatConversationsTest : ChatFixture() {
    @Test
    fun `editing a media turn keeps its files and deletes only the abandoned suffix`() =
        runTest(dispatcher) {
            loadModel()
            val context = ApplicationProvider.getApplicationContext<android.app.Application>()
            val attachmentDirectory = java.io.File(context.filesDir, "attachments").apply {
                mkdirs()
            }
            val original = java.io.File(attachmentDirectory, "original.jpg").apply {
                writeText("a")
            }
            val abandoned = java.io.File(attachmentDirectory, "abandoned.jpg").apply {
                writeText("b")
            }
            val originalPart = MessagePart.File(original.absolutePath, "image/jpeg", "original")
            val abandonedPart = MessagePart.File(abandoned.absolutePath, "image/jpeg", "abandoned")
            val id = chats.startConversation("original", "model-a")
            chats.addMessage(
                id,
                ChatRole.USER.wireName,
                "original",
                attachments = listOf(originalPart),
            )
            chats.addMessage(id, ChatRole.ASSISTANT.wireName, "old answer")
            chats.addMessage(
                id,
                ChatRole.USER.wireName,
                "later",
                attachments = listOf(abandonedPart),
            )

            viewModel.openConversation(id)
            settle(steps = FOLD_SETTLE_STEPS)
            viewModel.editAndResend(viewModel.uiState.value.transcript.first().id, "edited")
            settle(steps = FOLD_SETTLE_STEPS)

            assertThat(original.exists()).isTrue()
            assertThat(abandoned.exists()).isFalse()
            assertThat(viewModel.uiState.value.transcript.first().attachments.map { it.path })
                .containsExactly(original.absolutePath)
            val stored = chats.messages(id)
            assertThat(stored.first().text).isEqualTo("edited")
            assertThat(stored.first().attachments.decodeAttachments().map { it.path })
                .containsExactly(original.absolutePath)
            assertThat(stored.flatMap { it.attachments.decodeAttachments() }.map { it.path })
                .doesNotContain(abandoned.absolutePath)
        }

    @Test
    fun `two questions sent at once produce one conversation, not two`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true

        viewModel.send("First")
        viewModel.send("Second")
        settle()

        // canSend is claimed before any suspending work precisely so this cannot race into
        // two conversations, each with one of the questions in it.
        assertThat(database.conversations().observeAll().first()).hasSize(1)
        assertThat(viewModel.uiState.value.transcript.count { it.role == ChatRole.USER })
            .isEqualTo(1)
    }

    @Test
    fun `a process killed for memory comes back to the conversation it was in`() =
        runTest(dispatcher) {
            loadModel()
            viewModel.send("Remember me")
            settle()
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)

            // What Android does to an app holding a model in memory: the process goes, the
            // saved state does not. A new view model is what comes back.
            val revived = newViewModel(savedState)
            revived.loadModel(modelFile("model-a.gguf"))
            settle(steps = FOLD_SETTLE_STEPS)

            assertThat(revived.uiState.value.activeConversationId).isEqualTo(id)
            assertThat(revived.uiState.value.transcript.map { it.text })
                .contains("Remember me")
        }

    @Test
    fun `a fresh launch does not drag the last conversation back`() = runTest(dispatcher) {
        loadModel()
        viewModel.send("Old news")
        settle()

        // Swiped away rather than killed, so the saved state went with it.
        val relaunched = newViewModel(SavedStateHandle())
        relaunched.loadModel(modelFile("model-a.gguf"))
        settle(steps = FOLD_SETTLE_STEPS)

        assertThat(relaunched.uiState.value.transcript).isEmpty()
        assertThat(relaunched.uiState.value.activeConversationId).isNull()
    }

    @Test
    fun `a reopened conversation does not report an empty context`() = runTest(dispatcher) {
        loadModel()
        viewModel.send("Something worth a few tokens of context")
        settle()
        val id = requireNotNull(viewModel.uiState.value.activeConversationId)

        viewModel.openConversation(id)
        settle(steps = FOLD_SETTLE_STEPS)

        // The cache is empty on reopening because the model has not read this conversation
        // yet. The next turn will read all of it, so reporting nothing used told the
        // attachment budget the whole window was free.
        assertThat(viewModel.uiState.value.transcript).isNotEmpty()
        assertThat(viewModel.uiState.value.contextUsed).isGreaterThan(0)
    }

    @Test
    fun `a plan does not follow the user into another conversation`() = runTest(dispatcher) {
        loadModel()
        viewModel.send("First chat")
        settle()
        val first = requireNotNull(viewModel.uiState.value.activeConversationId)
        viewModel.newChat()
        settle()
        viewModel.send("Second chat")
        settle()
        // The plan belongs to the second conversation, which is the one that is open.
        viewModel.planning.propose("1. Read the notes\n2. Summarise them")
        assertThat(viewModel.planning.plan.value).isNotNull()

        viewModel.openConversation(first)
        settle(steps = FOLD_SETTLE_STEPS)

        // The board is one object for the whole app, so a plan left in it shows up in every
        // chat and gets pinned to the tail of every prompt. newChat cleared it and reopening
        // did not, which is the switch people actually use.
        assertThat(viewModel.planning.plan.value).isNull()
    }

    @Test
    fun `a model whose file has gone is let go of rather than answered with`() =
        runTest(dispatcher) {
            loadModel()
            // Deleted from the Models tab, or by a file manager, or on a card that was
            // pulled. The engine has it mapped and carries on as though nothing happened,
            // so the screen still names a model, Send is still live, and whatever the turn
            // eventually does with a file that is not there surfaces as a generic failure
            // minutes later.
            modelFile("model-a.gguf").delete()

            val sent = viewModel.send("Are you still there?")
            settle()

            assertThat(sent).isFalse()
            assertThat(viewModel.uiState.value.modelName).isNull()
            assertThat(viewModel.uiState.value.error).isNotNull()
        }

    @Test
    fun `regenerating against a database that will not answer gives the screen back`() =
        runTest(dispatcher) {
            loadModel()
            viewModel.send("Who was Ada Lovelace?")
            settle()
            writer.broken = true

            viewModel.regenerate()
            settle()

            // The busy flag is claimed before the read, so a read that throws left the
            // composer showing Stop with nothing running behind it: not a crash, a chat that
            // could never be used again without killing the app.
            assertThat(viewModel.uiState.value.isGenerating).isFalse()
            assertThat(viewModel.uiState.value.error).isNotNull()
        }

    @Test
    fun `a conversation list that will not load does not take the app with it`() =
        runTest(dispatcher) {
            // Writes were guarded and reads were not, which is half a rule: the same
            // unopenable database fails both, and this one fails in the view model's own
            // init, so the process dies before a screen exists to show anything on. With no
            // crash reporter that is a launch loop nobody can tell us about.
            writer.unreadable = true

            val opened = newViewModel(SavedStateHandle())
            settle()

            assertThat(opened.uiState.value.conversations).isEmpty()
            assertThat(opened.uiState.value.error).isNotNull()
        }

    @Test
    fun `a conversation that will not reopen says so and leaves the open one alone`() =
        runTest(dispatcher) {
            loadModel()
            viewModel.send("First chat")
            settle()
            val first = requireNotNull(viewModel.uiState.value.activeConversationId)
            writer.broken = true

            viewModel.openConversation(first)
            settle()

            assertThat(viewModel.uiState.value.error).isNotNull()
            assertThat(viewModel.uiState.value.transcript).isNotEmpty()
        }

    @Test
    fun `a message that could not be saved says so`() = runTest(dispatcher) {
        loadModel()
        // The disk, as far as the chat is concerned, is gone.
        writer.broken = true

        viewModel.send("This one will not survive")
        settle()

        // Every write here is launched and awaited by nobody, so a failure had no catch
        // above it and no handler on the scope: the process went with it. Short of that,
        // the message sat on screen for the session, was not there on reopening, and
        // nothing was ever said either way.
        assertThat(viewModel.uiState.value.error).contains("could not be saved")
        // Still on screen, and still answered. What somebody typed is not thrown away
        // because a write failed.
        assertThat(viewModel.uiState.value.transcript.first().text)
            .isEqualTo("This one will not survive")
    }

    @Test
    fun `a folded conversation still reports what its summary costs`() = runTest(dispatcher) {
        loadModel()
        repeat(4) { index ->
            viewModel.send("Question $index")
            settle()
        }

        // compactNow refuses while a turn is in flight, so the last one has to have landed
        // before it is asked, or the test proves nothing.
        settle(steps = FOLD_SETTLE_STEPS)
        assertThat(viewModel.uiState.value.isGenerating).isFalse()

        viewModel.compactNow()
        settle(steps = FOLD_SETTLE_STEPS)

        val state = viewModel.uiState.value
        assertThat(state.compaction).isNotNull()
        // Folding frees most of the window and not all of it: the summary and the turns
        // kept verbatim are still sent every turn. Reporting zero told everything that
        // sizes itself against the window that the whole of it was free, and the next
        // attachment was measured against a window that did not exist.
        assertThat(state.contextUsed).isGreaterThan(0)
        assertThat(state.contextUsed).isLessThan(state.contextSize)
    }

    @Test
    fun `a conversation that has to fold before the next turn folds instead of throwing`() =
        runTest(dispatcher) {
            loadModel()
            val window = requireNotNull(engine.loadedModel).contextSize
            // Past the policy's three quarters, so every completed turn leaves the context
            // reporting itself nearly full.
            engine.contextUsed = window * 4 / 5

            repeat(3) { index ->
                viewModel.send("Question $index")
                settle()
            }
            // Six entries is one short of what the policy will fold, so the fold that runs
            // after a turn found nothing to do and the context is still full. The fold that
            // matters is the one before the next turn, and that one runs while the composer
            // has already claimed the turn.
            assertThat(viewModel.uiState.value.transcript).hasSize(6)
            assertThat(viewModel.uiState.value.compaction).isNull()

            viewModel.send("The question that has to fold first")
            // A fold is a model call, a cache reset and a write of its own before the turn
            // it precedes even starts, so this needs more drains than an ordinary turn.
            settle(steps = FOLD_SETTLE_STEPS)

            // Nothing was thrown on the way. The guard this covers threw out of a coroutine
            // with no catch above it and no handler on the scope, which ends the process.
            assertThat(viewModel.uiState.value.error).isNull()

            // Folded through the fourth entry, which is the range that exists only while the
            // transcript is seven long: before the answer to this question was added. A
            // fold that had waited until after the turn would have covered more. This is
            // what says the fold ran when it was needed rather than after the fact.
            //
            // Four and not three, which is where the count alone would put it. Seven entries
            // is question, answer, three times over and a question, so a boundary at three
            // leaves an answer at the front of what is kept, and an answer at the front of a
            // prompt is dropped on the way out. That answer was in neither the summary nor
            // the prompt, and the model forgot what it had just said.
            val compaction = requireNotNull(viewModel.uiState.value.compaction)
            assertThat(compaction.foldedThroughIndex).isEqualTo(3)

            // And the point of folding first: the question still gets answered.
            assertThat(viewModel.uiState.value.transcript.last().role)
                .isEqualTo(ChatRole.ASSISTANT)
            assertThat(viewModel.uiState.value.isGenerating).isFalse()
        }

    @Test
    fun `a new chat clears the transcript and the conversation it belonged to`() =
        runTest(dispatcher) {
            loadModel()
            viewModel.send("First")
            settle()
            assertThat(viewModel.uiState.value.transcript).isNotEmpty()
            assertThat(viewModel.uiState.value.activeConversationId).isNotNull()

            viewModel.newChat()
            settle()

            assertThat(viewModel.uiState.value.transcript).isEmpty()
            assertThat(viewModel.uiState.value.activeConversationId).isNull()
            assertThat(viewModel.uiState.value.compaction).isNull()
            assertThat(engine.resetCount).isAtLeast(1)
        }

    @Test
    fun `deleting the open conversation removes it and leaves an empty chat`() =
        runTest(dispatcher) {
            loadModel()
            viewModel.send("Delete me")
            settle()
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)

            viewModel.deleteConversation(id)
            settle()

            assertThat(database.conversations().byId(id)).isNull()
            assertThat(viewModel.uiState.value.transcript).isEmpty()
            assertThat(viewModel.uiState.value.activeConversationId).isNull()
        }

    @Test
    fun `deleting the open conversation while it is answering leaves nothing behind`() =
        runTest(dispatcher) {
            loadModel()
            engine.hold = true
            viewModel.send("Delete me mid sentence")
            settle()
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)
            assertThat(viewModel.uiState.value.isGenerating).isTrue()

            // The reply is still being written when the row and its files go. This holds
            // the outcome, not the ordering: a test scheduler runs everything on one
            // virtual thread, so it cannot reproduce the interleaving that made stopping
            // last a bug, and it passes either way. It is here to catch the case being
            // broken outright, not to prove the ordering is right.
            viewModel.deleteConversation(id)
            settle()

            assertThat(database.conversations().byId(id)).isNull()
            assertThat(viewModel.uiState.value.isGenerating).isFalse()
            assertThat(viewModel.uiState.value.transcript).isEmpty()
            assertThat(database.messages().forConversation(id)).isEmpty()
        }

    @Test
    fun `deleting a conversation that is not open leaves the open one alone`() =
        runTest(dispatcher) {
            loadModel()
            viewModel.send("Older")
            settle()
            val older = requireNotNull(viewModel.uiState.value.activeConversationId)

            viewModel.newChat()
            settle()
            viewModel.send("Current")
            settle()
            val current = requireNotNull(viewModel.uiState.value.activeConversationId)

            viewModel.deleteConversation(older)
            settle()

            assertThat(viewModel.uiState.value.activeConversationId).isEqualTo(current)
            assertThat(viewModel.uiState.value.transcript).isNotEmpty()
        }

    @Test
    fun `switching model keeps the conversation and renames it`() = runTest(dispatcher) {
        loadModel(name = "model-a.gguf")
        viewModel.send("Carry me over")
        settle()
        val id = requireNotNull(viewModel.uiState.value.activeConversationId)
        val before = viewModel.uiState.value.transcript.size
        val resetsBefore = engine.resetCount

        loadModel(name = "model-b.gguf", keepConversation = true)
        settle()

        assertThat(viewModel.uiState.value.activeConversationId).isEqualTo(id)
        assertThat(viewModel.uiState.value.transcript).hasSize(before)
        assertThat(viewModel.uiState.value.modelName).isEqualTo("model-b")
        assertThat(database.conversations().byId(id)?.modelName).isEqualTo("model-b")
        // The cache belonged to the old weights and has to go, or the new model decodes
        // against positions it never wrote.
        assertThat(engine.resetCount).isGreaterThan(resetsBefore)
    }

    @Test
    fun `the new model is sent the turns the old one produced`() = runTest(dispatcher) {
        loadModel(name = "model-a.gguf")
        viewModel.send("Carry me over")
        settle()

        loadModel(name = "model-b.gguf", keepConversation = true)
        settle()
        viewModel.send("And this one")
        settle()

        // The cache is gone, so the transcript is the only thing carrying the conversation
        // across. If it is not resent, the new model answers the last question blind.
        val sent = engine.prompts.last().map { message -> message.text }
        assertThat(sent).contains("Carry me over")
        assertThat(sent).contains("And this one")
    }

    @Test
    fun `a second model tapped while the first is queued replaces it`() = runTest(dispatcher) {
        // Both requested before either reaches the engine. The second is what the user
        // wants, and the first is worth the seconds it would cost only if it is still the
        // answer by the time it runs.
        engine.loadDelayMs = LOAD_MS
        viewModel.loadModel(modelFile("model-a.gguf"))
        viewModel.loadModel(modelFile("model-b.gguf"))
        advanceUntilIdle()
        settle()

        assertThat(viewModel.uiState.value.modelName).isEqualTo("model-b")
        assertThat(engine.loads).containsExactly("model-b.gguf")
        engine.loadDelayMs = 0
    }

    @Test
    fun `a second model tapped mid load waits rather than running alongside`() =
        runTest(dispatcher) {
            engine.loadDelayMs = LOAD_MS
            viewModel.loadModel(modelFile("model-a.gguf"))
            // Far enough in that the first load is inside the engine and holding the lock.
            advanceTimeBy(LOAD_MS / 2)

            viewModel.loadModel(modelFile("model-b.gguf"))
            advanceUntilIdle()
            settle()

            // Both ran, in order, and neither was inside the engine while the other was.
            // Overlapping loads free the weights under each other and leave the engine
            // holding one model while the screen names the other.
            assertThat(engine.loads).containsExactly("model-a.gguf", "model-b.gguf").inOrder()
            assertThat(viewModel.uiState.value.modelName).isEqualTo("model-b")
            assertThat(viewModel.uiState.value.isLoadingModel).isFalse()
            engine.loadDelayMs = 0
        }

    @Test
    fun `a reply llama cpp parsed reopens as what was shown, not as raw tool syntax`() =
        runTest(dispatcher) {
            // About what is shown and stored, not about running anything, so the tool is
            // switched off. Left on, the markup in the raw stream is now read as a call the
            // model failed to format, and the turn spends its repair round on it.
            ToolSwitches(ApplicationProvider.getApplicationContext())
                .setEnabled(StubTool.definition.name, false)
            loadModel()
            engine.hold = true
            viewModel.send("What is the weather")
            settle()

            // What a model with a recognised tool format streams, against what the engine's
            // parser hands back once it has lifted the call out.
            engine.emit("<think>They want weather.</think>Checking. ")
            engine.emit("""<tool_call>{"name":"weather"}</tool_call>""")
            engine.finish(content = "Checking. ", reasoning = "They want weather.")
            settle()

            val id = requireNotNull(viewModel.uiState.value.activeConversationId)
            val shown = viewModel.uiState.value.transcript.last()
            assertThat(shown.answer).isEqualTo("Checking. ")
            assertThat(shown.reasoning).isEqualTo("They want weather.")

            // Reopening re-reads the row and re-parses it. It has to arrive at the same
            // answer, or the chat changes what it says the moment it is closed.
            val stored = awaitMessages(id, count = 2).last()
            viewModel.openConversation(id)
            settle()

            val reopened = viewModel.uiState.value.transcript.last()
            assertThat(reopened.text).isEqualTo(stored.text)
            assertThat(reopened.answer).isEqualTo(shown.answer)
            assertThat(reopened.reasoning).isEqualTo(shown.reasoning)
            assertThat(reopened.answer).doesNotContain("tool_call")
        }

    @Test
    fun `loading a model without keeping the conversation starts a fresh one`() =
        runTest(dispatcher) {
            loadModel(name = "model-a.gguf")
            viewModel.send("Old chat")
            settle()

            loadModel(name = "model-b.gguf", keepConversation = false)
            settle()

            assertThat(viewModel.uiState.value.transcript).isEmpty()
            assertThat(viewModel.uiState.value.activeConversationId).isNull()
        }

    @Test
    fun `a reply is persisted against the conversation that asked for it`() = runTest(dispatcher) {
        loadModel()
        viewModel.send("Question")
        settle()
        val id = requireNotNull(viewModel.uiState.value.activeConversationId)

        val stored = awaitMessages(id, count = 2)
        assertThat(stored.map { it.role }).containsExactly("user", "assistant").inOrder()
        assertThat(stored.map { it.text })
            .containsExactly("Question", viewModel.uiState.value.transcript.last().text)
            .inOrder()
        // Written twice on purpose: the row carries this reply's numbers, the ledger the
        // totals, so deleting the chat later does not un-count the work.
        assertThat(database.usage().observeAll().first()).isNotEmpty()
    }

    @Test
    fun `regenerating replaces the stored reply instead of adding one`() = runTest(dispatcher) {
        loadModel()
        viewModel.send("Question")
        settle()
        val id = requireNotNull(viewModel.uiState.value.activeConversationId)

        // No waiting for the first reply to reach the table. Regenerate can be tapped the
        // moment the reply appears, and its delete has to queue behind the insert rather
        // than read past it and leave the discarded reply in storage.
        viewModel.regenerate()
        settle()

        val stored = awaitMessages(id, count = 2)
        assertThat(stored.map { it.role }).containsExactly("user", "assistant").inOrder()
        assertThat(viewModel.uiState.value.transcript.count { it.role == ChatRole.ASSISTANT })
            .isEqualTo(1)
    }

    @Test
    fun `a stopped reply keeps its place when the next question follows immediately`() =
        runTest(dispatcher) {
            loadModel()
            engine.hold = true
            viewModel.send("First")
            settle()
            engine.emit("Partial answer.")
            advanceUntilIdle()

            // Stop and ask again, which is what happens when a reply goes wrong. The
            // partial belongs between the two questions, not after both of them.
            viewModel.stop()
            settle()
            engine.hold = false
            viewModel.send("Second")
            settle()

            val id = requireNotNull(viewModel.uiState.value.activeConversationId)
            val stored = awaitMessages(id, count = 4)
            // Out of order here means the chat reopens as a different conversation from
            // the one on screen, and the model is resent that different conversation.
            assertThat(stored.map { it.role })
                .containsExactly("user", "assistant", "user", "assistant").inOrder()
            assertThat(stored.map { it.text })
                .containsExactly("First", "Partial answer.", "Second", "A short answer.")
                .inOrder()
        }

    @Test
    fun `a failed load does not leave the old model on screen`() = runTest(dispatcher) {
        loadModel(name = "model-a.gguf")
        engine.failNextLoad = true

        loadModel(name = "missing.gguf")
        settle()

        assertThat(viewModel.uiState.value.modelName).isNull()
        assertThat(viewModel.uiState.value.error).isNotNull()
    }
}

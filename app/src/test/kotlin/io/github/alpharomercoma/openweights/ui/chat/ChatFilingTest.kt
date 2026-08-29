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
import io.github.alpharomercoma.openweights.core.common.context.GoalState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Naming a conversation, pinning it, and filing it away.
 *
 * The three things the drawer's overflow menu does that are not deleting, and what each of
 * them is allowed to do to the chat on screen: archiving the open one closes it, because a
 * conversation that has left the list should not be the one still filling the screen, and
 * pinning it must not, because a pin says where a chat sits and nothing about whether it is
 * open. What the storage does with each is in `ChatRepositoryTest`; this is the half that
 * decides what the screen shows afterwards. The harness is [ChatFixture].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatFilingTest : ChatFixture() {
    @Test
    fun `archiving the open conversation leaves the screen on a fresh one`() = runTest(dispatcher) {
        // Same reasoning as deleting the open one: a chat that is no longer in the list
        // should not be the chat still filling the screen. Everything in it survives,
        // which is the whole difference from deleting.
        loadModel()
        viewModel.send("File me away")
        settle()
        val id = requireNotNull(viewModel.uiState.value.activeConversationId)

        viewModel.setConversationArchived(id, archived = true)
        settle()

        assertThat(database.conversations().byId(id)!!.archivedAt).isNotNull()
        assertThat(database.messages().forConversation(id)).isNotEmpty()
        assertThat(viewModel.uiState.value.activeConversationId).isNull()
        assertThat(viewModel.uiState.value.transcript).isEmpty()
    }

    @Test
    fun `archiving a chat that is still answering does not file itself back out`() =
        runTest(dispatcher) {
            // Every message write calls `touch`, and `touch` clears `archivedAt` on
            // purpose. A reply still unwinding is a message write the user cannot see
            // coming, so archiving mid-answer used to undo itself a moment later.
            loadModel()
            engine.hold = true
            viewModel.send("File me away mid sentence")
            settle()
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)
            assertThat(viewModel.uiState.value.isGenerating).isTrue()

            viewModel.setConversationArchived(id, archived = true)
            settle()

            assertThat(database.conversations().byId(id)!!.archivedAt).isNotNull()
            assertThat(viewModel.uiState.value.isGenerating).isFalse()
        }

    @Test
    fun `archiving a conversation that is not open leaves the open one alone`() =
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

            viewModel.setConversationArchived(older, archived = true)
            settle()

            assertThat(viewModel.uiState.value.activeConversationId).isEqualTo(current)
            assertThat(viewModel.uiState.value.transcript).isNotEmpty()
        }

    @Test
    fun `pinning the open conversation does not close it`() = runTest(dispatcher) {
        // Pinning says where a chat sits in the list, not whether it is in the list, so
        // unlike archiving it has no business touching what is on screen.
        loadModel()
        viewModel.send("Keep me at the top")
        settle()
        val id = requireNotNull(viewModel.uiState.value.activeConversationId)

        viewModel.setConversationPinned(id, pinned = true)
        settle()

        assertThat(viewModel.uiState.value.activeConversationId).isEqualTo(id)
        assertThat(viewModel.uiState.value.conversations.single { it.id == id }.isPinned).isTrue()
    }

    @Test
    fun `a renamed conversation keeps its new name in the list`() = runTest(dispatcher) {
        loadModel()
        viewModel.send("What is a KV cache?")
        settle()
        val id = requireNotNull(viewModel.uiState.value.activeConversationId)

        viewModel.renameConversation(id, "Cache notes")
        settle()

        assertThat(viewModel.uiState.value.conversations.single { it.id == id }.title)
            .isEqualTo("Cache notes")
    }

    @Test
    fun `a chat put away is not brought back by the process being killed`() = runTest(dispatcher) {
        // The saved-state handle remembers the last conversation on purpose, so that
        // being killed for memory does not read as having lost the chat. Archiving is
        // the one thing that has to override that: the row still exists, so a restore
        // found it and reopened it, and an archived conversation filled the screen
        // while being hidden in the list.
        loadModel()
        viewModel.send("File me away")
        settle()
        val id = requireNotNull(viewModel.uiState.value.activeConversationId)

        viewModel.setConversationArchived(id, archived = true)
        settle()

        val revived = newViewModel(savedState)
        revived.loadModel(modelFile("model-a.gguf"))
        settle(steps = FOLD_SETTLE_STEPS)

        assertThat(revived.uiState.value.activeConversationId).isNull()
        assertThat(revived.uiState.value.transcript).isEmpty()
    }

    @Test
    fun `an archive that could not be written says so and keeps the chat on screen`() =
        runTest(dispatcher) {
            // The failure used to be reported and then immediately undone: newChat() ran
            // regardless, replacing the conversation with a blank one on the strength of a
            // filing that never happened, and clearing the only message explaining why.
            loadModel()
            viewModel.send("File me away")
            settle()
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)

            // What a full disk looks like from here.
            filing.broken = true

            viewModel.setConversationArchived(id, archived = true)
            settle()

            assertThat(viewModel.uiState.value.activeConversationId).isEqualTo(id)
            assertThat(viewModel.uiState.value.error).isNotNull()
        }

    @Test
    fun `archiving stops a goal that would otherwise file the chat straight back out`() =
        runTest(dispatcher) {
            // A goal reads the board rather than the transcript to decide whether to take
            // another step, so stopping only the turn in flight left it free to join the
            // wait, find itself still marked running, and write another message into the
            // conversation being filed away — and every message write clears `archivedAt`.
            //
            // The write is broken on purpose, and that is what makes this a test rather
            // than a tautology: on the happy path `newChat` stops the goal a moment later
            // anyway, so the assertion would pass whether or not archiving stopped it
            // first. With the write refused there is no `newChat`, and the only thing that
            // could have stopped the goal is the archive itself.
            loadModel()
            engine.hold = true
            viewModel.startGoal("Something long")
            settle()
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)
            assertThat(goals.goal.value?.isRunning).isTrue()

            filing.broken = true
            viewModel.setConversationArchived(id, archived = true)
            settle()

            // STOPPED, not merely "not running". Stopping the turn alone left the loop to
            // take its own next step and give up, which lands on HALTED — the goal ran on
            // past the archive, which is the whole defect.
            assertThat(goals.goal.value?.state).isEqualTo(GoalState.STOPPED)
        }

    @Test
    fun `a chat archived while the model is still loading is not opened when it finishes`() =
        runTest(dispatcher) {
            // The gap between a cold start and a loaded model is long enough to open the
            // drawer and file something away, and during it the conversation to reopen is
            // held in a field rather than in `conversationId` — so the archive path saw
            // nothing open, cleared nothing, and the load reopened it moments later.
            loadModel()
            viewModel.send("File me away")
            settle()
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)

            val revived = newViewModel(savedState)
            revived.setConversationArchived(id, archived = true)
            settle()

            revived.loadModel(modelFile("model-a.gguf"))
            settle(steps = FOLD_SETTLE_STEPS)

            assertThat(revived.uiState.value.activeConversationId).isNull()
            assertThat(revived.uiState.value.transcript).isEmpty()
        }

    @Test
    fun `a conversation deleted while it is being reopened does not land on screen`() =
        runTest(dispatcher) {
            // The inverse of the race the delete re-read closed. Reopening reads the row,
            // then does several more queued reads and two joins before it adopts the id;
            // a delete confirmed in that window left the deleted conversation on screen,
            // and its next message had no parent row to hang from.
            //
            // This holds the outcome, not the ordering, and it passes against the unfixed
            // code: one virtual thread cannot put the delete inside that window, so the
            // early existence check catches it here every time. It is here to catch the
            // case being broken outright, the same way the delete-mid-answer test is.
            loadModel()
            viewModel.send("Delete me")
            settle()
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)
            viewModel.newChat()
            settle()

            viewModel.deleteConversation(id)
            viewModel.openConversation(id)
            settle()

            assertThat(database.conversations().byId(id)).isNull()
            assertThat(viewModel.uiState.value.activeConversationId).isNull()
            assertThat(viewModel.uiState.value.transcript).isEmpty()
        }
}

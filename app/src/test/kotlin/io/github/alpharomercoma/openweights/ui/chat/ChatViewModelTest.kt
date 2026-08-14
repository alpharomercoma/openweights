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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.context.CompactionPolicy
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.data.ChatRepository
import io.github.alpharomercoma.openweights.core.data.Clock
import io.github.alpharomercoma.openweights.core.data.ModelPreferencesRepository
import io.github.alpharomercoma.openweights.core.data.db.MessageEntity
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.device.DeviceProfiler
import io.github.alpharomercoma.openweights.core.device.ThermalPolicy
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import io.github.alpharomercoma.openweights.model.AttachmentStore
import io.github.alpharomercoma.openweights.model.ModelStore
import io.github.alpharomercoma.openweights.ui.ReplyNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * What the chat screen has to get right.
 *
 * Driven through the real view model with a real database and a fake engine, because every
 * behaviour here is about the wiring between them: whether stop actually stops, whether a
 * new chat actually clears, whether one conversation's work can land in another.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models: File = Files.createTempDirectory("openweights-models").toFile()
    private lateinit var database: OpenWeightsDatabase
    private lateinit var engine: FakeInferenceEngine
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        database = Room.inMemoryDatabaseBuilder(context, OpenWeightsDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        engine = FakeInferenceEngine()
        val chats = ChatRepository(database, Clock.System)
        val registry = ToolRegistry(emptyList())
        viewModel = ChatViewModel(
            runtime = ModelRuntime(
                engine = engine,
                modelStore = ModelStore(context),
                preferences = ModelPreferencesRepository(context),
                thermal = ThermalPolicy(context, DeviceProfiler(context)),
            ),
            compactor = ConversationCompactor(engine, CompactionPolicy()),
            attachments = AttachmentStore(context),
            chats = chats,
            turns = TurnRunner(engine, registry, ToolSwitches(context)),
            notifier = ReplyNotifier(context),
        )
    }

    @After
    fun tearDown() {
        database.close()
        models.deleteRecursively()
        Dispatchers.resetMain()
    }

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

            // Folded through the third entry, which is the range that exists only while the
            // transcript is seven long: before the answer to this question was added. A
            // fold that had waited until after the turn would have covered four. This is
            // what says the fold ran when it was needed rather than after the fact.
            val compaction = requireNotNull(viewModel.uiState.value.compaction)
            assertThat(compaction.foldedThroughIndex).isEqualTo(2)

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

    @Test
    fun `a failed load does not leave the old model on screen`() = runTest(dispatcher) {
        loadModel(name = "model-a.gguf")
        engine.failNextLoad = true

        loadModel(name = "missing.gguf")
        settle()

        assertThat(viewModel.uiState.value.modelName).isNull()
        assertThat(viewModel.uiState.value.error).isNotNull()
    }

    /** Loads a throwaway file through the real code path and waits for it to settle. */

    private fun TestScope.loadModel(
        name: String = "model-a.gguf",
        keepConversation: Boolean = false,
    ) {
        viewModel.loadModel(modelFile(name), keepConversation = keepConversation)
        settle()
    }

    /**
     * A throwaway file with an exact name.
     *
     * Not createTempFile: the view model derives the displayed model name from the file
     * name, and createTempFile appends random digits to it.
     */
    private fun modelFile(name: String): File =
        File(models, name).apply { writeText("not a real model") }

    /**
     * Waits for a conversation to hold [count] messages and returns them.
     *
     * Persistence is launched separately from the state update that precedes it, so
     * reading the table straight after an assertion on the screen can catch it one write
     * short. This waits for the write rather than assuming a fixed number of drains is
     * enough.
     */
    private suspend fun TestScope.awaitMessages(id: Long, count: Int): List<MessageEntity> {
        repeat(AWAIT_STEPS) {
            val rows = database.messages().forConversation(id)
            if (rows.size >= count) return rows
            settle(steps = 2)
        }
        return database.messages().forConversation(id)
    }

    /**
     * Runs the view model's work until the state stops changing.
     *
     * advanceUntilIdle alone is not enough here. The view model reads preferences from
     * DataStore, which runs on a real dispatcher, so a coroutine can be parked off the test
     * scheduler with nothing left for the scheduler to run. Alternating a short real wait
     * with a drain lets that work land and its continuation be picked up.
     */
    private fun TestScope.settle(steps: Int = SETTLE_STEPS) {
        repeat(steps) {
            advanceUntilIdle()
            Thread.sleep(SETTLE_PAUSE_MS)
        }
        advanceUntilIdle()
    }

    private companion object {
        /** Enough passes for a DataStore read and its continuation to both complete. */
        const val SETTLE_STEPS = 6
        const val SETTLE_PAUSE_MS = 20L

        /** How many times to re-check the table before giving up and asserting on it. */
        const val AWAIT_STEPS = 20

        /** A fold runs the model, resets the cache and writes, all before its turn starts. */
        const val FOLD_SETTLE_STEPS = 30

        /** Virtual milliseconds a held load takes, long enough to interleave a second. */
        const val LOAD_MS = 500L
    }
}

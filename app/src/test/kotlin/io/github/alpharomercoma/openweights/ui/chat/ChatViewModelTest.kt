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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.context.CompactionPolicy
import io.github.alpharomercoma.openweights.core.data.ChatRepository
import io.github.alpharomercoma.openweights.core.data.Clock
import io.github.alpharomercoma.openweights.core.data.ModelPreferencesRepository
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.device.DeviceProfiler
import io.github.alpharomercoma.openweights.core.device.ThermalPolicy
import io.github.alpharomercoma.openweights.model.AttachmentStore
import io.github.alpharomercoma.openweights.model.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
        viewModel = ChatViewModel(
            engine = engine,
            compactor = ConversationCompactor(engine, CompactionPolicy()),
            modelStore = ModelStore(context),
            attachments = AttachmentStore(context),
            chats = chats,
            modelPreferences = ModelPreferencesRepository(context),
            thermalPolicy = ThermalPolicy(context, DeviceProfiler(context)),
        )
    }

    @After
    fun tearDown() {
        database.close()
        models.deleteRecursively()
        Dispatchers.resetMain()
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

        loadModel(name = "model-b.gguf", keepConversation = true)
        settle()

        assertThat(viewModel.uiState.value.activeConversationId).isEqualTo(id)
        assertThat(viewModel.uiState.value.transcript).hasSize(before)
        assertThat(viewModel.uiState.value.modelName).isEqualTo("model-b")
        assertThat(database.conversations().byId(id)?.modelName).isEqualTo("model-b")
        // The cache belonged to the old weights and has to go, or the new model decodes
        // against positions it never wrote.
        assertThat(engine.resetCount).isAtLeast(1)
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

        val stored = database.messages().forConversation(id)
        assertThat(stored.map { it.role }).containsExactly("user", "assistant").inOrder()
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
        // An exact name, not createTempFile: the view model derives the displayed model
        // name from the file name, and createTempFile appends random digits to it.
        val file = File(models, name)
        file.writeText("not a real model")
        viewModel.loadModel(file, keepConversation = keepConversation)
        settle()
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
    }
}

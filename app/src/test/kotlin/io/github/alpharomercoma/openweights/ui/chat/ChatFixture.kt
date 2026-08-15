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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.alpharomercoma.openweights.core.common.context.CompactionPolicy
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.data.ChatRepository
import io.github.alpharomercoma.openweights.core.data.Clock
import io.github.alpharomercoma.openweights.core.data.ModelPreferencesRepository
import io.github.alpharomercoma.openweights.core.data.db.ConversationEntity
import io.github.alpharomercoma.openweights.core.data.db.MessageEntity
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.device.DeviceProfiler
import io.github.alpharomercoma.openweights.core.device.ThermalPolicy
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.OffDeviceConsent
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.Tool
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import io.github.alpharomercoma.openweights.model.AttachmentStore
import io.github.alpharomercoma.openweights.model.ModelStore
import io.github.alpharomercoma.openweights.ui.ReplyNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.io.File
import java.nio.file.Files

/**
 * The chat screen, wired to a real database and a fake engine.
 *
 * Shared because the wiring is the point of every test over it: a real Room database, a real
 * write queue that can be told the disk is gone, and an engine that says exactly what came
 * back. Building that twice would be two harnesses to keep in step, and the second one would
 * drift.
 *
 * Two suites sit on it. [ChatViewModelTest] is about one turn: what stop leaves behind, what
 * reaches the screen, what a tool pass is stored as. [ChatConversationsTest] is about what
 * happens either side of a turn: opening, deleting, folding, switching model, and surviving
 * the process being killed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ChatFixture {
    protected val dispatcher = StandardTestDispatcher()
    protected val models: File = Files.createTempDirectory("openweights-models").toFile()
    protected lateinit var database: OpenWeightsDatabase
    protected lateinit var engine: FakeInferenceEngine
    protected lateinit var writer: FailableWriter
    protected lateinit var viewModel: ChatViewModel
    protected lateinit var chats: ChatRepository
    protected val savedState = SavedStateHandle()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        database = Room.inMemoryDatabaseBuilder(context, OpenWeightsDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        engine = FakeInferenceEngine()
        chats = ChatRepository(database, Clock.System)
        writer = FailableWriter(chats)
        // Registered but unreachable unless a test says the model supports tools, so the
        // tool loop is available to the tests that want it and invisible to the rest.
        viewModel = newViewModel(savedState)
    }

    /** Another view model over the same storage, which is what survives process death. */
    protected fun newViewModel(state: SavedStateHandle): ChatViewModel {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        return ChatViewModel(
            runtime = ModelRuntime(
                engine = engine,
                modelStore = ModelStore(context),
                preferences = ModelPreferencesRepository(context),
                thermal = ThermalPolicy(context, DeviceProfiler(context)),
            ),
            compactor = ConversationCompactor(engine, CompactionPolicy()),
            staging = Staging(AttachmentStore(context)),
            writer = writer,
            turns = TurnRunner(
                engine,
                ToolRegistry(listOf(StubTool)),
                ToolSwitches(context),
                PlanBoard(),
                AskBoard(),
                settledConsent(context),
            ),
            notifier = ReplyNotifier(context),
            savedState = state,
        )
    }

    @After
    fun tearDown() {
        database.close()
        models.deleteRecursively()
        Dispatchers.resetMain()
    }

    /** Loads a throwaway file through the real code path and waits for it to settle. */

    protected fun TestScope.loadModel(
        name: String = "model-a.gguf",
        keepConversation: Boolean = false,
    ) {
        viewModel.loadModel(modelFile(name), keepConversation = keepConversation)
        // Waited for rather than drained a fixed number of times. A load reads settings and
        // the usage ledger before it reaches the engine, and a helper that assumed a set
        // number of passes started failing the moment one more round trip was added: send
        // was then called while the model was still loading, refused, and the test failed
        // somewhere else entirely.
        repeat(AWAIT_STEPS) {
            if (viewModel.uiState.value.modelName != null) return
            settle(steps = 2)
        }
    }

    /**
     * A throwaway file with an exact name.
     *
     * Not createTempFile: the view model derives the displayed model name from the file
     * name, and createTempFile appends random digits to it.
     */
    protected fun modelFile(name: String): File =
        File(models, name).apply { writeText("not a real model") }

    /** A write queue that can be told the disk is gone. */
    class FailableWriter(chats: ChatRepository) : ChatWriter(chats) {
        /** Set to make every write from here on throw, as a full disk would. */
        var broken = false

        /**
         * Set to make the conversation list throw, as a database that will not open would.
         *
         * Separate from [broken] because it fails in a different place: the list is a flow
         * collected for the lifetime of the view model, not a call anybody awaits.
         */
        var unreadable = false

        override suspend fun <T> inOrder(work: suspend ChatRepository.() -> T): T {
            if (broken) error("the disk would not take it")
            return super.inOrder(work)
        }

        override fun conversations(): Flow<List<ConversationEntity>> =
            if (unreadable) flow { error("the database would not open") } else super.conversations()
    }

    /** Something for a scripted call to land on. What it returns does not matter here. */
    object StubTool : Tool {
        override val definition = ToolDefinition(
            name = "web_search",
            description = "Search the web.",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}""",
        )

        override suspend fun run(call: ToolCall): String = "Ada Lovelace, 1815 to 1852."
    }

    /**
     * Waits for a conversation to hold [count] messages and returns them.
     *
     * Persistence is launched separately from the state update that precedes it, so
     * reading the table straight after an assertion on the screen can catch it one write
     * short. This waits for the write rather than assuming a fixed number of drains is
     * enough.
     */
    protected suspend fun TestScope.awaitMessages(id: Long, count: Int): List<MessageEntity> {
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
    protected fun TestScope.settle(steps: Int = SETTLE_STEPS) {
        repeat(steps) {
            advanceUntilIdle()
            Thread.sleep(SETTLE_PAUSE_MS)
        }
        advanceUntilIdle()
    }

    protected companion object {
        /** Enough passes for a DataStore read and its continuation to both complete. */
        const val SETTLE_STEPS = 6
        const val SETTLE_PAUSE_MS = 20L

        /** How many times to re-check the table before giving up and asserting on it. */
        const val AWAIT_STEPS = 20

        /** A fold runs the model, resets the cache and writes, all before its turn starts. */
        const val FOLD_SETTLE_STEPS = 30

        /** What [FakeInferenceEngine] reports for every completed pass. */
        const val FAKE_TOKENS_PER_PASS = 4

        /** One to ask for the tool, one to answer with what it returned. */
        const val PASSES = 2

        /** Virtual milliseconds a held load takes, long enough to interleave a second. */
        const val LOAD_MS = 500L
    }
}

/**
 * Consent already given, which is every turn after the first.
 *
 * The first one is [AgentRunnerTest]'s business: what it does is ask.
 */
internal fun settledConsent(context: android.content.Context): OffDeviceConsent =
    OffDeviceConsent(context, ToolSwitches(context)).apply { settle("web_search", true) }

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
import androidx.lifecycle.viewModelScope
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
import io.github.alpharomercoma.openweights.core.device.FitEstimator
import io.github.alpharomercoma.openweights.core.device.ThermalPolicy
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.GoalBoard
import io.github.alpharomercoma.openweights.core.tools.Memory
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.Tool
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import io.github.alpharomercoma.openweights.model.AttachmentStore
import io.github.alpharomercoma.openweights.model.ModelStore
import io.github.alpharomercoma.openweights.ui.ReplyNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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

    /**
     * The board a goal reads its plan off, held so a test can be the model that proposed it.
     *
     * A goal will not start without a plan, and a plan arrives through a tool call the model
     * makes. Scripting that through the fake engine would be testing the tool parser; what
     * these tests are about is what the loop does once a plan exists.
     */
    protected lateinit var plans: PlanBoard
    protected lateinit var goals: GoalBoard
    protected lateinit var turns: TurnRunner
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
        plans = PlanBoard()
        goals = GoalBoard()
        turns = TurnRunner(
            engine,
            ToolRegistry(listOf(StubTool)),
            ToolSwitches(context),
            plans,
            AskBoard(),
        )
        return ChatViewModel(
            runtime = ModelRuntime(
                engine = engine,
                modelStore = ModelStore(context),
                preferences = ModelPreferencesRepository(context),
                thermal = ThermalPolicy(context, DeviceProfiler(context)),
                windows = ContextWindows(FitEstimator(), DeviceProfiler(context)),
            ),
            compactor = ConversationCompactor(engine, CompactionPolicy()),
            staging = Staging(AttachmentStore(context), context),
            writer = writer,
            turns = turns,
            notifier = ReplyNotifier(context),
            goals = goals,
            memory = Memory(context),
            // Robolectric has no service to start, and GenerationService swallows the
            // failure on purpose: a turn that cannot raise its own priority still has to
            // produce the reply.
            appContext = context,
            savedState = state,
        )
    }

    @After
    fun tearDown() {
        // Cancelled, not drained, and the difference is what took two attempts to get
        // right. A view model scope can still hold queued work when a test ends; once the
        // main dispatcher is reset that work has nowhere to run and surfaces as "uncaught
        // exceptions before the test started" in whichever class happens to run next, which
        // is a failure with no relationship to the test reporting it.
        //
        // Draining looked like the fix and was not: `advanceUntilIdle` *runs* the queued
        // work, and a coroutine written to be cancelled at the end of a screen's life
        // throws when it is instead allowed to finish against a torn-down fixture. Killing
        // the work is what a real view model gets when its screen goes away.
        // The scope, not only its children. Cancelling children leaves the scope itself
        // active, so anything that launches afterwards, a flow collection restarting or a
        // callback firing late, gets a live scope on a dispatcher whose test has ended and
        // throws into the next class. Cancelling the scope makes every later launch a no-op,
        // which is what a real view model gets when its screen is destroyed.
        runCatching { viewModel.viewModelScope.cancel() }

        // Then let the cancellation actually land before the database goes. Cancelling is a
        // signal, not an event: a coroutine parked on the test dispatcher does not observe
        // it until something runs the scheduler, and one parked on a real dispatcher, which
        // is where the DataStore reads go, does not observe it until it is resumed there. If
        // either resumes after `close`, it touches a database that is gone and throws into
        // whichever class runs next, which is the failure this whole block exists to stop.
        //
        // This is not the draining the comment above rejects. That was draining *instead of*
        // cancelling, which lets work meant to die run to completion against a fixture that
        // is already half gone. Draining *after* cancelling only gives work that is already
        // dead somewhere to unwind, which it has to have.
        repeat(TEARDOWN_STEPS) {
            runCatching { dispatcher.scheduler.advanceUntilIdle() }
            Thread.sleep(SETTLE_PAUSE_MS)
        }
        runCatching { dispatcher.scheduler.advanceUntilIdle() }

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
        //
        // Both halves are checked, and the second half is why: the name is now set on the
        // way in so the top bar keeps it while weights are remapped, so a name on its own no
        // longer means a model is loaded. Waiting on the name alone returned here mid-load
        // and every send that followed was refused.
        repeat(AWAIT_STEPS) {
            val state = viewModel.uiState.value
            if (state.modelName != null && !state.isLoadingModel) return
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
     * Waits for the engine to have been asked to load [count] times.
     *
     * For the same reason as [awaitMessages], and one step further removed: a reload driven
     * by a setting is decided only after the setting has been written to DataStore, which
     * runs on a real dispatcher. Asserting straight after a fixed number of drains passed
     * alone and failed inside the full suite, which is the least useful way for a test to
     * fail.
     */
    protected suspend fun TestScope.awaitLoads(count: Int): List<String> {
        repeat(AWAIT_STEPS) {
            if (engine.loads.size >= count) return engine.loads
            settle(steps = 2)
        }
        return engine.loads
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
        /**
         * Enough passes for every piece of off-scheduler work in a turn to land.
         *
         * Six covered a DataStore read and its continuation. A turn now also waits for the
         * reply to be written before it reports itself finished, which is a database round
         * trip on a real dispatcher and needs its own alternations; at six, a third turn in
         * a row simply had not happened yet when the assertions ran. Ten is the floor
         * measured here and this is above it, because the failure mode of too few is a test
         * that passes on this machine and not on a slower one.
         */
        const val SETTLE_STEPS = 16
        const val SETTLE_PAUSE_MS = 20L

        /** How many times to re-check the table before giving up and asserting on it. */
        const val AWAIT_STEPS = 20

        /**
         * Passes given to work that has been cancelled and has to unwind.
         *
         * Small, because nothing here is being waited *for*: the work is already dead and
         * this only gives it somewhere to notice. Three covers a suspension on a real
         * dispatcher and its continuation, which is the shape the DataStore reads have.
         */
        const val TEARDOWN_STEPS = 3

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

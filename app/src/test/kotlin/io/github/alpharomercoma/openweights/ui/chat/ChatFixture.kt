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
import io.github.alpharomercoma.openweights.core.data.ArchivedConversations
import io.github.alpharomercoma.openweights.core.data.ChatRepository
import io.github.alpharomercoma.openweights.core.data.Clock
import io.github.alpharomercoma.openweights.core.data.ConversationFiling
import io.github.alpharomercoma.openweights.core.data.ModelPreferencesRepository
import io.github.alpharomercoma.openweights.core.data.db.ConversationEntity
import io.github.alpharomercoma.openweights.core.data.db.MessageEntity
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.device.DeviceProfiler
import io.github.alpharomercoma.openweights.core.device.FitEstimator
import io.github.alpharomercoma.openweights.core.device.ThermalPolicy
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.GoalBoard
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.Tool
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import io.github.alpharomercoma.openweights.model.AttachmentStore
import io.github.alpharomercoma.openweights.model.ModelStore
import io.github.alpharomercoma.openweights.ui.ReplyNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

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
    protected lateinit var filing: FailableFiling
    protected lateinit var viewModel: ChatViewModel
    protected lateinit var chats: ChatRepository

    /** Room's executor, wrapped so a settle can see a query or a write still in flight. */
    private lateinit var databaseWork: TrackedExecutor

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
    protected lateinit var switches: ToolSwitches
    protected lateinit var grant: WorkspaceGrant
    protected val savedState = SavedStateHandle()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        databaseWork = TrackedExecutor(
            Executors.newFixedThreadPool(DATABASE_THREADS) { Thread(it, "openweights-test-db") },
        )
        database = Room.inMemoryDatabaseBuilder(context, OpenWeightsDatabase::class.java)
            .allowMainThreadQueries()
            // Room's own default is a four-thread pool exactly like this one. The wrapper is
            // the only difference, and it is how settle knows a write has not landed yet.
            .setQueryExecutor(databaseWork)
            .setTransactionExecutor(databaseWork)
            .build()

        engine = FakeInferenceEngine()
        chats = ChatRepository(context, database, Clock.System)
        writer = FailableWriter(chats)
        filing = FailableFiling(database)
        // Registered but unreachable unless a test says the model supports tools, so the
        // tool loop is available to the tests that want it and invisible to the rest.
        viewModel = newViewModel(savedState)
    }

    /** Another view model over the same storage, which is what survives process death. */
    protected fun newViewModel(state: SavedStateHandle): ChatViewModel {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        plans = PlanBoard()
        goals = GoalBoard()
        switches = ToolSwitches(context)
        grant = WorkspaceGrant(context)
        turns = TurnRunner(
            engine,
            ToolRegistry(listOf(StubTool)),
            switches,
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
            filing = filing,
            archive = ArchivedConversations(database),
            turns = turns,
            notifier = ReplyNotifier(context),
            goals = goals,
            toolSwitches = switches,
            workspaceGrant = grant,
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
        // dead somewhere to unwind, which it has to have. It used to get three fixed pauses
        // for that; now it gets until nothing is running anywhere, which is the same thing
        // asked rather than guessed.
        runCatching { drainUntilQuiet(TEARDOWN_CEILING_MS) }
        runCatching { dispatcher.scheduler.advanceUntilIdle() }

        database.close()
        databaseWork.shutdown()
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
    /** The same idea as [FailableWriter], for the writes that do not go through it. */
    class FailableFiling(database: OpenWeightsDatabase) :
        ConversationFiling(database, Clock.System) {
        /** Set to make every filing edit from here on throw, as a full disk would. */
        var broken = false

        override suspend fun rename(id: Long, title: String): Boolean {
            if (broken) error("the disk would not take it")
            return super.rename(id, title)
        }

        override suspend fun setPinned(id: Long, pinned: Boolean) {
            if (broken) error("the disk would not take it")
            super.setPinned(id, pinned)
        }

        override suspend fun setArchived(id: Long, archived: Boolean) {
            if (broken) error("the disk would not take it")
            super.setArchived(id, archived)
        }
    }

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

        /** A singleton reused across every test in the JVM, so each test resets this itself. */
        var fails = false

        /** What it hands back; a test that needs a page-sized result sets it, and resets it. */
        var answer: String = DEFAULT_ANSWER

        /** Runs as the call lands; a test that needs the world to change mid-turn sets it. */
        var onRun: (() -> Unit)? = null

        override suspend fun run(call: ToolCall): String {
            if (fails) error("the tool would not run")
            onRun?.invoke()
            return answer
        }

        const val DEFAULT_ANSWER = "Ada Lovelace, 1815 to 1852."
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
     * Runs the view model's work until there is none left anywhere it can be.
     *
     * advanceUntilIdle alone is not enough here. The view model reads preferences from
     * DataStore and writes through Room, both on real dispatchers, so a coroutine can be
     * parked off the test scheduler with nothing left for the scheduler to run, and come
     * back a moment later with more. This used to alternate a drain with a fixed real wait,
     * [steps] times, which was a guess at how long the round trips take: too few and a test
     * failed on a slower machine, and the sixteen it took to be safe cost 320 ms on every
     * call whether anything was running or not. Two hundred call sites made that over a
     * minute of the suite spent asleep.
     *
     * Now it asks. Three places can hold work in flight — the pool that Dispatchers.IO and
     * Default share, Room's executor, and the scheduler's own queue — and each one can say
     * whether it does. The loop drains, looks at all three, and returns once they have been
     * empty together on [QUIET_CHECKS] consecutive looks a millisecond apart, which is the
     * margin for a worker that has been handed a task and not yet woken to run it. A
     * ceiling still bounds it, sized by [steps] so that the callers that asked for the
     * longest waits get the most patience, but none of it is spent unless something is
     * genuinely still running.
     */
    protected fun TestScope.settle(steps: Int = SETTLE_STEPS) {
        drainUntilQuiet(ceilingMs = steps * STEP_CEILING_MS)
    }

    /**
     * Runs the work until [condition] holds, and fails if it never does.
     *
     * [settle] waits for everything to finish; this waits for one thing to become true,
     * which is the tool for a state the work only passes through, and for a wait whose
     * failure should say what it was waiting for rather than surface as whatever the next
     * assertion happened to trip over. It arrived when staging a seven-file batch, seven
     * sequential IO round trips, fit inside the old fixed-step grace on a fast laptop and
     * not on the two-core CI runner: five of seven staged, red on exactly one machine.
     * Exits the moment the condition is true, so the fast machine stays fast; the ceiling
     * only prices the slow one.
     */
    protected fun TestScope.settleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + CONDITION_CEILING_MS * NANOS_PER_MILLI
        while (true) {
            dispatcher.scheduler.advanceUntilIdle()
            if (condition()) return
            if (System.nanoTime() >= deadline) break
            Thread.sleep(POLL_MS)
        }
        dispatcher.scheduler.advanceUntilIdle()
        check(condition()) { "state never settled into the expected shape" }
    }

    /**
     * Drains and re-drains until nothing is in flight anywhere, or [ceilingMs] passes.
     *
     * The order of the looks matters. The scheduler is drained first, then the pool and
     * the database are looked at, and the scheduler's queue is asked last: a worker that
     * finishes between the look and the question hands the scheduler its continuation,
     * and asking afterwards is what makes that continuation count as work still to do.
     * The one thing no order can see is a task handed to a parked worker that has not yet
     * woken, which is why quiet has to hold on consecutive looks before it is believed.
     */
    private fun drainUntilQuiet(ceilingMs: Long) {
        val deadline = System.nanoTime() + ceilingMs * NANOS_PER_MILLI
        var quietLooks = 0
        while (System.nanoTime() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            val quiet = !CoroutinePool.isBusy() &&
                databaseWork.isIdle &&
                dispatcher.scheduler.hasNothingQueued()
            quietLooks = if (quiet) quietLooks + 1 else 0
            if (quietLooks >= QUIET_CHECKS) return
            Thread.sleep(POLL_MS)
        }
    }

    /** Whether the scheduler holds nothing at all, delayed or otherwise. */
    private fun TestCoroutineScheduler.hasNothingQueued(): Boolean =
        SCHEDULER_IS_IDLE.invoke(this) as Boolean

    /**
     * An executor that can say whether anything handed to it is still queued or running.
     *
     * Room runs its queries and transactions on whatever executor it is given and says
     * nothing about them until the caller's continuation lands back on the scheduler. In
     * between, this count is the only way a settle can see the write it is waiting for.
     */
    private class TrackedExecutor(private val pool: ExecutorService) : Executor {
        private val inFlight = AtomicInteger()

        val isIdle: Boolean get() = inFlight.get() == 0

        override fun execute(command: Runnable) {
            inFlight.incrementAndGet()
            try {
                pool.execute {
                    try {
                        command.run()
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
            } catch (rejected: RejectedExecutionException) {
                inFlight.decrementAndGet()
                throw rejected
            }
        }

        fun shutdown() {
            pool.shutdown()
        }
    }

    protected companion object {
        /**
         * The default patience, in steps of [STEP_CEILING_MS].
         *
         * Sixteen was the number of drain-and-sleep alternations a turn needed back when
         * settle waited blind: six covered a DataStore read and its continuation, and the
         * reply write a turn waits for before reporting itself finished took the rest. It
         * stays as the unit the call sites are written in.
         */
        const val SETTLE_STEPS = 16

        /**
         * The ceiling a settle gets per step, now that the steps are no longer waited out.
         *
         * The callers that asked for the most steps were the ones with the most work to
         * wait for, so the same number sizes how long settle may keep looking. Half a
         * second a step is twenty-five times what a step used to sleep, and none of it is
         * spent unless something is still running.
         */
        const val STEP_CEILING_MS = 500L

        /** How often settle looks, and the spacing of the looks that confirm quiet. */
        const val POLL_MS = 1L

        /**
         * Consecutive quiet looks before settle believes it.
         *
         * A worker that has just been handed a task reads as parked until the OS wakes it,
         * so one look can miss work a few microseconds from starting. Three looks a
         * millisecond apart make that miss need a wake-up slower than two milliseconds.
         */
        const val QUIET_CHECKS = 3

        /** How many times to re-check the table before giving up and asserting on it. */
        const val AWAIT_STEPS = 20

        /** [settleUntil]'s ceiling: two seconds of real time, paid only when needed. */
        const val CONDITION_CEILING_MS = 2_000L

        /**
         * How long cancelled work gets to unwind before the database goes.
         *
         * Nothing here is being waited *for*: the work is already dead and this only gives
         * it somewhere to notice, so the usual case is three quiet looks and the ceiling is
         * for the one that is not.
         */
        const val TEARDOWN_CEILING_MS = 2_000L

        /** Room's own default pool is this wide, and the fixture's stand-in matches it. */
        const val DATABASE_THREADS = 4

        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * The scheduler's own answer to "is anything queued", which it keeps internal.
         *
         * `advanceUntilIdle` runs what is queued and returns, and a worker thread can queue
         * the next thing a microsecond later with nothing public to say so. The internal
         * check is one synchronized `isEmpty` over the event heap, resolved once here so
         * that a coroutines upgrade which renames it fails on the first settle with this
         * message, rather than as tests that quietly stop waiting.
         */
        private val SCHEDULER_IS_IDLE: Method = runCatching {
            TestCoroutineScheduler::class.java.getMethod("isIdle\$kotlinx_coroutines_test")
        }.getOrElse {
            error("TestCoroutineScheduler no longer exposes isIdle; update ChatFixture.settle")
        }

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
 * The pool Dispatchers.IO and Dispatchers.Default share, asked whether any worker is busy.
 *
 * Nothing public answers that. Each worker is a thread carrying a public state field, and
 * two of its states mean "running a task": one for work that holds a CPU permit, one for
 * work that is allowed to block, which is what DataStore's reads and the file copies are.
 * Everything else — parked, dormant, terminated — is a worker with nothing to do.
 */
private object CoroutinePool {
    private val worker: Class<*> = runCatching {
        Class.forName("kotlinx.coroutines.scheduling.CoroutineScheduler\$Worker")
    }.getOrElse { error("the coroutine scheduler's worker moved; update ChatFixture.settle") }

    private val state: Field = worker.getField("state")

    private val busy = setOf("CPU_ACQUIRED", "BLOCKING")

    fun isBusy(): Boolean = liveThreads().any { thread ->
        worker.isInstance(thread) && state.get(thread).toString() in busy
    }

    private fun liveThreads(): List<Thread> {
        var root: ThreadGroup = Thread.currentThread().threadGroup
        while (root.parent != null) root = root.parent
        var threads = arrayOfNulls<Thread>(root.activeCount() + THREAD_SLACK)
        var count = root.enumerate(threads, true)
        while (count == threads.size) {
            threads = arrayOfNulls(threads.size * 2)
            count = root.enumerate(threads, true)
        }
        return threads.take(count).filterNotNull()
    }

    /** Room for threads started between the count and the enumeration. */
    private const val THREAD_SLACK = 16
}

/**
 * Consent already given, which is every turn after the first.
 *
 * The first one is [AgentRunnerTest]'s business: what it does is ask.
 */

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

package io.github.alpharomercoma.openweights.watch

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.context.WatchState
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.data.ModelPreferencesRepository
import io.github.alpharomercoma.openweights.core.data.WatchRepository
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.device.DeviceProfiler
import io.github.alpharomercoma.openweights.core.device.FitEstimator
import io.github.alpharomercoma.openweights.core.device.ThermalPolicy
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import io.github.alpharomercoma.openweights.model.ModelStore
import io.github.alpharomercoma.openweights.ui.chat.ContextWindows
import io.github.alpharomercoma.openweights.ui.chat.FakeInferenceEngine
import io.github.alpharomercoma.openweights.ui.chat.ModelRuntime
import io.github.alpharomercoma.openweights.ui.chat.TurnRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * The scheduler's own ticker, on a clock a test controls.
 *
 * [WatchRunnerTest] covers one tick in isolation; this covers the loop around it, which is
 * where the fix in cfa7afa lives. Real time made this loop untestable before: the ticker's
 * `delay` is a genuine suspend on a genuine clock, so seeing whether it noticed a stopped
 * watch within one tick or only at the top of the next period meant waiting minutes. An
 * `@ApplicationScope CoroutineScope` handed in by Hilt in production and a `TestScope` handed
 * in here is the seam that makes the difference observable in milliseconds of wall time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WatchSchedulerTest {
    private lateinit var database: OpenWeightsDatabase
    private lateinit var watches: WatchRepository
    private lateinit var engine: FakeInferenceEngine
    private lateinit var runner: WatchRunner
    private val models: File = Files.createTempDirectory("openweights-watch-scheduler").toFile()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        database = Room.inMemoryDatabaseBuilder(context, OpenWeightsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        watches = WatchRepository(database)
        engine = FakeInferenceEngine()
        val runtime = ModelRuntime(
            engine = engine,
            modelStore = ModelStore(context),
            preferences = ModelPreferencesRepository(context),
            thermal = ThermalPolicy(context, DeviceProfiler(context)),
            windows = ContextWindows(FitEstimator(), DeviceProfiler(context)),
        )
        runner = WatchRunner(
            watches = watches,
            runtime = runtime,
            turns = TurnRunner(
                engine,
                ToolRegistry(emptyList()),
                ToolSwitches(context),
                PlanBoard(),
                AskBoard(),
            ),
            appContext = context,
        )
    }

    @After
    fun tearDown() {
        database.close()
        models.deleteRecursively()
    }

    private suspend fun loadedEngine() {
        val file = File(models, "model-a.gguf").apply { writeText("not a real model") }
        engine.load(file, ModelLoadParams(), null)
    }

    /**
     * [scope] is [TestScope.backgroundScope], not the test body's own scope. The ticker
     * inside [WatchScheduler] runs for as long as the watch is active, which in a real app is
     * exactly the point, but `runTest` requires every child of the test body's own scope to
     * finish before the test does. `backgroundScope` shares the same virtual clock and is
     * cancelled automatically when the test ends, which is what a ticker that outlives the
     * scenario being tested actually needs.
     */
    private fun schedulerOf(
        context: android.content.Context,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = WatchScheduler(
        appContext = context,
        watches = watches,
        runner = javax.inject.Provider { runner },
        scope = scope,
    )

    /**
     * Drains whatever is due at the current virtual time, alternated with a short real wait,
     * the same way [io.github.alpharomercoma.openweights.ui.chat.ChatFixture.settle] does. A
     * tick reads model preferences out of DataStore, which does its own I/O on a real
     * dispatcher outside the test scheduler's virtual clock, so draining once right after
     * moving the clock is a race against a background thread that has not posted its
     * continuation back yet.
     *
     * `runCurrent`, not `advanceUntilIdle`. The ticker's own loop is meant to run forever, so
     * there is always another `delay` queued behind the one that just fired; asking the
     * scheduler to run until nothing is left runs every future tick it can reach in one call,
     * which is the same infinite loop from the other direction. `runCurrent` only runs what
     * is due *now*, which is what a fixed number of retries here can safely be given.
     */
    private suspend fun TestScope.settleOneTick() {
        repeat(SETTLE_STEPS) {
            runCurrent()
            Thread.sleep(SETTLE_PAUSE_MS)
        }
        runCurrent()
    }

    @Test
    fun `a fast watch that fails three times in a row stops ticking within that tick`() = runTest {
        loadedEngine()
        engine.failChat = true
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val scheduler = schedulerOf(context, backgroundScope)
        val watch = requireNotNull(
            watches.add("Check the tides", everyMinutes = 1, now = 0),
        )

        scheduler.schedule(watch)
        // Three periods, one failure recorded in each: the third failure is also the one
        // that stops the watch, so it is the last tick this ticker should ever make.
        repeat(3) {
            advanceTimeBy(MINUTE + 1)
            settleOneTick()
        }

        val runsAfterThreeFailures = database.watches().observeRuns(watch.id, 100).first()
        assertThat(runsAfterThreeFailures).hasSize(3)
        assertThat(requireNotNull(watches.byId(watch.id)).state)
            .isEqualTo(WatchState.FAILED)

        // The old behaviour: a fourth `delay` would have elapsed before the ticker
        // noticed the watch it had already stopped and looked again anyway. Advancing
        // several more periods and finding the run count unchanged is what the
        // immediate-break fix in cfa7afa buys over that.
        advanceTimeBy(5 * MINUTE)
        settleOneTick()

        assertThat(database.watches().observeRuns(watch.id, 100).first())
            .hasSize(3)
    }

    @Test
    fun `a healthy fast watch keeps ticking on its own schedule`() = runTest {
        loadedEngine()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val scheduler = schedulerOf(context, backgroundScope)
        val watch = requireNotNull(watches.add("Check the tides", everyMinutes = 1, now = 0))

        scheduler.schedule(watch)
        repeat(3) {
            advanceTimeBy(MINUTE + 1)
            settleOneTick()
        }

        val after = requireNotNull(watches.byId(watch.id))
        assertThat(after.state).isEqualTo(WatchState.ACTIVE)
        assertThat(database.watches().observeRuns(watch.id, 100).first()).hasSize(3)
    }

    @Test
    fun `cancelling a fast watch stops its ticker`() = runTest {
        loadedEngine()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val scheduler = schedulerOf(context, backgroundScope)
        val watch = requireNotNull(watches.add("Check the tides", everyMinutes = 1, now = 0))

        // Cancelled before its first period even elapses, rather than after some number of
        // ticks, so there is no question of a tick already in flight when cancel() lands —
        // this is a test of the ticker never starting again, not of exactly which tick a
        // race let finish.
        scheduler.schedule(watch)
        scheduler.cancel(watch.id)
        advanceTimeBy(5 * MINUTE)
        settleOneTick()

        assertThat(database.watches().observeRuns(watch.id, 100).first()).isEmpty()
    }

    private companion object {
        const val MINUTE = 60_000L
        const val SETTLE_STEPS = 16
        const val SETTLE_PAUSE_MS = 20L
    }
}

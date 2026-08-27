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

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.NotificationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.context.WatchOutcome
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * A watch runs for weeks with nobody looking, and its own guardrail can turn on it.
 *
 * Three failures in a row stop a watch, which is right for a check that cannot work and
 * wrong for one that was interrupted. The two arrive at the same place: a tick is a model
 * turn, a model turn takes a while, and anything that stops the process mid-turn, the user
 * pausing the watch, WorkManager reclaiming its worker, the ticker being torn down, cancels
 * the coroutine underneath it. Counting that as the check having failed spends the
 * guardrail on the one thing it was never meant to catch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WatchRunnerTest {
    private lateinit var database: OpenWeightsDatabase
    private lateinit var watches: WatchRepository
    private lateinit var engine: FakeInferenceEngine
    private lateinit var runner: WatchRunner
    private val models: File = Files.createTempDirectory("openweights-watch").toFile()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        // Granted explicitly: Robolectric does not grant a manifest-declared runtime
        // permission by default, and the alert notification checks for it before posting.
        org.robolectric.Shadows.shadowOf(context).grantPermissions(POST_NOTIFICATIONS)
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

    @Test
    fun `a tick cancelled mid turn is not counted as a failure`() = runTest {
        loadedEngine()
        engine.hold = true
        val watch = requireNotNull(watches.add("Check the tides", everyMinutes = 15, now = 0))

        val ticking = launch { runner.tick(watch.id, now = 1) }
        advanceUntilIdle()
        // The turn is in flight and nothing has come back. Whatever stops the process here,
        // a paused watch or a reclaimed worker, arrives as a cancellation.
        ticking.cancel()
        advanceUntilIdle()

        val after = requireNotNull(watches.byId(watch.id))
        assertThat(after.consecutiveFailures).isEqualTo(0)
        assertThat(after.isActive).isTrue()
    }

    @Test
    fun `three interruptions do not stop a watch that never failed`() = runTest {
        loadedEngine()
        engine.hold = true
        val watch = requireNotNull(watches.add("Check the tides", everyMinutes = 15, now = 0))

        repeat(3) { round ->
            val ticking = launch { runner.tick(watch.id, now = round.toLong()) }
            advanceUntilIdle()
            ticking.cancel()
            advanceUntilIdle()
        }

        val after = requireNotNull(watches.byId(watch.id))
        assertThat(after.isActive).isTrue()
    }

    @Test
    fun `a tick with no model loaded is skipped rather than failed`() = runTest {
        val watch = requireNotNull(watches.add("Check the tides", everyMinutes = 15, now = 0))

        val outcome = runner.tick(watch.id, now = 1)

        assertThat(outcome).isEqualTo(WatchOutcome.SKIPPED)
        assertThat(requireNotNull(watches.byId(watch.id)).consecutiveFailures).isEqualTo(0)
    }

    @Test
    fun `a tick for a watch that is gone reports nothing to reschedule`() = runTest {
        assertThat(runner.tick(watchId = 404, now = 1)).isNull()
    }

    /**
     * The one alert a watch is allowed to make noise about: a check that actually ran and
     * has something to say. A skipped tick — busy, hot, low battery — is the ordinary cost
     * of running unattended and must stay silent, or the feature trains people to mute it.
     */
    @Test
    fun `a check that actually runs posts one notification with its finding`() = runTest {
        loadedEngine()
        val watch = requireNotNull(watches.add("Check the tides", everyMinutes = 15, now = 0))

        val outcome = runner.tick(watch.id, now = 1)

        assertThat(outcome).isEqualTo(WatchOutcome.CHECKED)
        val manager = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(NotificationManager::class.java)
        val posted = org.robolectric.Shadows.shadowOf(manager).allNotifications
        assertThat(posted).hasSize(1)
        assertThat(posted.single().extras.getString(android.app.Notification.EXTRA_TITLE))
            .isEqualTo("Check the tides")
    }

    @Test
    fun `a skipped tick posts no notification`() = runTest {
        val watch = requireNotNull(watches.add("Check the tides", everyMinutes = 15, now = 0))

        val outcome = runner.tick(watch.id, now = 1)

        assertThat(outcome).isEqualTo(WatchOutcome.SKIPPED)
        val manager = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(NotificationManager::class.java)
        assertThat(org.robolectric.Shadows.shadowOf(manager).allNotifications).isEmpty()
    }

    /**
     * The exact shape a live device test found: a watch created from "remind me to
     * stretch" stores that phrasing verbatim, and on its own tick answered "I'm sorry, but
     * I can't set reminders" — true of the assistant in an ordinary turn, and false of a
     * tick, which is the reminder already firing. The tick's own system prompt has to say
     * so, since nothing forces every watch's task to have been rephrased as a check at
     * creation time.
     */
    @Test
    fun `the tick prompt tells the model a reminder-worded task is already due`() = runTest {
        loadedEngine()
        val watch = requireNotNull(watches.add("Remind me to stretch", everyMinutes = 5, now = 0))

        runner.tick(watch.id, now = 1)

        val systemPrompt = engine.prompts.last().first { it.role == ChatRole.SYSTEM }.text
        assertThat(systemPrompt).contains("due now")
        assertThat(systemPrompt).contains("not something you need a tool for")
    }
}

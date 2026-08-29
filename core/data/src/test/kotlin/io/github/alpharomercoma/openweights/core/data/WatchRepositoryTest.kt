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

package io.github.alpharomercoma.openweights.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.context.Watch
import io.github.alpharomercoma.openweights.core.common.context.WatchOutcome
import io.github.alpharomercoma.openweights.core.common.context.WatchState
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The guardrails, which are the whole of what makes an unattended feature safe to ship.
 *
 * A watch spends battery on a schedule with nobody looking at it, so every one of these is
 * a rule about when it must stop doing that.
 */
@RunWith(RobolectricTestRunner::class)
class WatchRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        OpenWeightsDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val repository = WatchRepository(database)

    @After
    fun tearDown() = database.close()

    @Test
    fun `a watch survives being written and read back`() {
        runBlocking {
            val started = repository.add("check the tide", everyMinutes = 30, now = 1_000)

            assertThat(started).isNotNull()
            assertThat(repository.active().map { it.task }).containsExactly("check the tide")
        }
    }

    @Test
    fun `only so many may run at once`() {
        runBlocking {
            repeat(Watch.MAX_ACTIVE) { repository.add("check $it", everyMinutes = 30, now = 1_000) }

            // Refused in the store rather than in the interface, so the model's tool and the
            // user's command cannot disagree about the limit.
            assertThat(repository.add("one too many", everyMinutes = 30, now = 1_000)).isNull()
        }
    }

    @Test
    fun `a stopped watch stops counting against the limit`() {
        runBlocking {
            val first = repository.add("check one", everyMinutes = 30, now = 1_000)!!
            repeat(Watch.MAX_ACTIVE - 1) { repository.add("check $it", everyMinutes = 30, now = 1) }
            repository.stop(first.id)

            assertThat(repository.add("room now", everyMinutes = 30, now = 2_000)).isNotNull()
        }
    }

    @Test
    fun `three failures in a row stop the watch`() {
        runBlocking {
            val watch = repository.add("check the thing", everyMinutes = 5, now = 1_000)!!

            repeat(Watch.MAX_CONSECUTIVE_FAILURES) {
                repository.record(watch.id, 2_000, WatchOutcome.FAILED, "no")
            }

            assertThat(repository.byId(watch.id)?.state).isEqualTo(WatchState.FAILED)
            assertThat(repository.active()).isEmpty()
        }
    }

    @Test
    fun `a success resets the run of failures`() {
        runBlocking {
            // In a row, not in total. A watch that fails twice a day for a week is working.
            val watch = repository.add("check the thing", everyMinutes = 5, now = 1_000)!!

            repository.record(watch.id, 2_000, WatchOutcome.FAILED, "no")
            repository.record(watch.id, 3_000, WatchOutcome.FAILED, "no")
            repository.record(watch.id, 4_000, WatchOutcome.CHECKED, "all quiet")
            repository.record(watch.id, 5_000, WatchOutcome.FAILED, "no")

            assertThat(repository.byId(watch.id)?.state).isEqualTo(WatchState.ACTIVE)
        }
    }

    @Test
    fun `a skipped tick is not a failure and does not count as a run`() {
        runBlocking {
            // The phone being busy, hot or flat is not the watch's fault, and treating it as one
            // would stop a healthy watch after three busy minutes.
            val watch = repository.add("check the thing", everyMinutes = 1, now = 1_000)!!

            repeat(Watch.MAX_CONSECUTIVE_FAILURES + 2) {
                repository.record(watch.id, 2_000, WatchOutcome.SKIPPED, "busy")
            }

            val after = repository.byId(watch.id)!!
            assertThat(after.state).isEqualTo(WatchState.ACTIVE)
            assertThat(after.runs).isEqualTo(0)
            assertThat(after.lastRunAt).isNull()
        }
    }

    @Test
    fun `a watch faster than the scheduler floor says so`() {
        // The interface has to tell the user a notification is coming, and this is what it
        // asks. Android will not repeat work below fifteen minutes; see WatchScheduler.
        assertThat(Watch(task = "x", everyMinutes = 5).needsForegroundService).isTrue()
        assertThat(Watch(task = "x", everyMinutes = 15).needsForegroundService).isFalse()
        assertThat(Watch(task = "x", everyMinutes = 60).needsForegroundService).isFalse()
    }

    @Test
    fun `history is bounded so a fast watch is not a growing database`() {
        runBlocking {
            val watch = repository.add("check the thing", everyMinutes = 1, now = 1_000)!!

            repeat(30) { repository.record(watch.id, 2_000L + it, WatchOutcome.CHECKED, "run $it") }

            val kept = repository.observeRuns(watch.id, limit = 100).first()
            assertThat(kept).hasSize(20)
            assertThat(kept.first().summary).isEqualTo("run 29")
        }
    }

    @Test
    fun `a watch spends its budget and stops itself`() {
        runBlocking {
            val watch = repository.add("check the tide", everyMinutes = 1, now = NOW)!!

            repeat(Watch.MAX_RUNS) {
                repository.record(watch.id, NOW + it, WatchOutcome.CHECKED, "run $it")
            }

            // In the write that counted the last one, not a period later: the ticker is
            // what would notice a period later, and the ticker is what a process death
            // takes away.
            val after = repository.byId(watch.id)!!
            assertThat(after.state).isEqualTo(WatchState.EXPIRED)
            assertThat(after.runs).isEqualTo(Watch.MAX_RUNS)
        }
    }

    @Test
    fun `a skipped tick spends no budget`() {
        runBlocking {
            val watch = repository.add("check the tide", everyMinutes = 1, now = NOW)!!

            // A busy engine, a low battery: routine, and not the user's watch being used up.
            repeat(Watch.MAX_RUNS + 5) {
                repository.record(watch.id, NOW + it, WatchOutcome.SKIPPED, "busy")
            }

            val after = repository.byId(watch.id)!!
            assertThat(after.runs).isEqualTo(0)
            assertThat(after.state).isEqualTo(WatchState.ACTIVE)
        }
    }

    @Test
    fun `a watch that outlives its window is ended by the sweep`() {
        runBlocking {
            val old = repository.add("check the tide", everyMinutes = 60, now = NOW)!!
            val fresh = repository.add("check the sky", everyMinutes = 60, now = NOW)!!

            // Two days and a minute later, with the app having been shut for all of it: the
            // clock half of the bound needs no tick to pass, so nothing else would end it.
            val ended = repository.expireLapsed(old.expiresAt + 60_000)

            assertThat(ended.map { it.id }).containsExactly(old.id, fresh.id)
            assertThat(repository.byId(old.id)!!.state).isEqualTo(WatchState.EXPIRED)
        }
    }

    @Test
    fun `the sweep does not overwrite why a watch already ended`() {
        runBlocking {
            val watch = repository.add("check the tide", everyMinutes = 60, now = NOW)!!
            repeat(Watch.MAX_CONSECUTIVE_FAILURES) {
                repository.record(watch.id, NOW + it, WatchOutcome.FAILED, "no")
            }

            repository.expireLapsed(watch.expiresAt + 60_000)

            // "It broke" and "it ran out of time" send a person to different places.
            assertThat(repository.byId(watch.id)!!.state).isEqualTo(WatchState.FAILED)
        }
    }

    @Test
    fun `the next check is written down, and moves even when a tick is skipped`() {
        runBlocking {
            val watch = repository.add("check the tide", everyMinutes = 5, now = NOW)!!
            assertThat(watch.nextRunAt).isEqualTo(NOW + 5 * 60_000)

            // A skip leaves lastRunAt alone by design, but the ticker still sleeps a whole
            // period — so a countdown derived from the last *run* would sit in the past
            // saying a check was owed when none was.
            val after = repository.record(
                watch.id,
                NOW + 5 * 60_000,
                WatchOutcome.SKIPPED,
                "busy",
            )!!

            assertThat(after.lastRunAt).isNull()
            assertThat(after.dueAt).isEqualTo(NOW + 10 * 60_000)
        }
    }

    @Test
    fun `a restarted ticker moves the deadline to a period from now`() {
        runBlocking {
            val watch = repository.add("check the tide", everyMinutes = 5, now = NOW)!!

            repository.reschedule(watch.id, NOW + 3 * 60_000)

            assertThat(repository.byId(watch.id)!!.dueAt).isEqualTo(NOW + 8 * 60_000)
        }
    }

    @Test
    fun `a stopped watch is not given a new deadline`() {
        runBlocking {
            val watch = repository.add("check the tide", everyMinutes = 5, now = NOW)!!
            repository.stop(watch.id)

            repository.reschedule(watch.id, NOW + 3 * 60_000)

            assertThat(repository.byId(watch.id)!!.dueAt).isEqualTo(NOW + 5 * 60_000)
        }
    }

    @Test
    fun `two things ending the same watch at once do not overwrite each other`() {
        runBlocking {
            val watch = repository.add("check the tide", everyMinutes = 60, now = NOW)!!

            // The startup sweep and the user's Stop button, arriving together. Whichever
            // lands first is the reason, and the second must not repaint it: the guard is a
            // WHERE clause rather than a read followed by a write, so there is no window
            // between deciding and writing for the other one to fit into.
            repository.stop(watch.id, WatchState.EXPIRED)
            repository.stop(watch.id, WatchState.STOPPED)

            assertThat(repository.byId(watch.id)!!.state).isEqualTo(WatchState.EXPIRED)
        }
    }

    @Test
    fun `a deadline is not written onto a watch that has stopped`() {
        runBlocking {
            val watch = repository.add("check the tide", everyMinutes = 5, now = NOW)!!
            repository.stop(watch.id)

            // A ticker unwinding after cancellation can still reach this. Reviving a
            // stopped watch by writing to it would be the worst kind of bug here: a check
            // running on a schedule the user believes they ended.
            repository.reschedule(watch.id, NOW + 3 * 60_000)

            val after = repository.byId(watch.id)!!
            assertThat(after.state).isEqualTo(WatchState.STOPPED)
            assertThat(after.dueAt).isEqualTo(NOW + 5 * 60_000)
        }
    }

    private companion object {
        /** One clock for the watch and for the judgement about it. See `Watch.isSpent`. */
        val NOW: Long = System.currentTimeMillis()
    }
}

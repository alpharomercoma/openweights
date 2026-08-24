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

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.context.WatchOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * What one delivered tick means for the schedule behind it.
 *
 * A watch has two things keeping it alive: the row in the database and the periodic job in
 * WorkManager, and nothing keeps them in step except this decision. Tearing the job down is
 * therefore not recoverable from inside the app: the watch is still listed, still says
 * active, and never runs again, and the only clue is that nothing ever appears in its
 * history. So it may only happen for the one reason it was written for, which is a tick that
 * ran and found nothing left to run.
 *
 * Everything else, a database that would not answer, an engine that threw, the system taking
 * the worker back mid-turn, says nothing at all about whether the watch is still wanted.
 */
class WatchWorkerTest {
    @Test
    fun `a tick that finds the watch gone ends the schedule`() = runTest {
        assertThat(scheduleIsOver { null }).isTrue()
    }

    @Test
    fun `a tick that ran keeps the schedule`() = runTest {
        assertThat(scheduleIsOver { WatchOutcome.CHECKED }).isFalse()
        assertThat(scheduleIsOver { WatchOutcome.SKIPPED }).isFalse()
        assertThat(scheduleIsOver { WatchOutcome.FAILED }).isFalse()
    }

    @Test
    fun `a tick that threw keeps the schedule`() = runTest {
        // A failing check already has a home: three in a row stop the watch through its own
        // counter, which is recoverable and visible. Unscheduling it here instead is neither.
        assertThat(scheduleIsOver { error("the database would not answer") }).isFalse()
    }

    @Test
    fun `a tick the system stopped keeps the schedule and stays cancelled`() = runTest {
        // The worker being reclaimed says nothing about the watch. Rethrown rather than
        // swallowed, so the run is recorded as stopped rather than as having completed.
        var thrown: Throwable? = null
        try {
            scheduleIsOver { throw CancellationException("worker stopped") }
        } catch (cancelled: CancellationException) {
            thrown = cancelled
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
    }
}

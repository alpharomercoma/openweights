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

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.alpharomercoma.openweights.core.common.context.WatchOutcome
import kotlinx.coroutines.CancellationException

/**
 * One scheduled tick, delivered by the system.
 *
 * Always returns success, including when the check failed. A `Result.retry` would ask
 * WorkManager to run the same tick again with backoff, and a watch already has a schedule:
 * the retry would land beside the next tick rather than instead of it, and a watch that is
 * failing would double its rate exactly when it should not. Failure is recorded in the
 * watch's own history, which is where anybody looking will look.
 */
@HiltWorker
class WatchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val runner: WatchRunner,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(WATCH_ID, -1)
        if (id < 0) return Result.success()

        // A tick that ran nothing is either a watch that is over or a backstop arriving
        // ahead of the deadline the in-process ticker is sleeping toward, and only the
        // first may end the schedule. Read as one, a five-minute watch's fifteen-minute
        // backstop unscheduled itself on its first early tick, and after the process died
        // the watch was dead until the next launch.
        if (scheduleIsOver { runner.tick(id) } && !runner.stillWanted(id)) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(workName(id))
        }
        return Result.success()
    }

    companion object {
        const val WATCH_ID = "watchId"

        /** One name per watch, so scheduling twice replaces rather than doubles. */
        fun workName(watchId: Long) = "watch-$watchId"
    }
}

/**
 * Whether one delivered tick means the schedule behind it should be torn down.
 *
 * Only a tick that ran and found the watch gone or no longer active, which is what a null
 * outcome means. Without that the periodic job outlives the watch: one that stopped itself
 * after three failures left a job behind that woke the device every fifteen minutes,
 * forever, to discover each time that there was nothing to do. Nothing else cancels it,
 * since the in-process ticker notices the state and exits and WorkManager is not watching
 * the database.
 *
 * Every other ending keeps it, and that half is the one worth being careful about, because
 * unscheduling cannot be recovered from inside the app: the watch is still listed, still
 * says active, and never runs again, and the only clue is a history that stopped growing.
 * A check that threw already has a home in the watch's own failure counter, which is
 * visible and recoverable. A worker the system took back says nothing about the watch at
 * all, and its cancellation is passed on rather than swallowed, so the run is recorded as
 * stopped rather than as finished.
 */
internal suspend fun scheduleIsOver(tick: suspend () -> WatchOutcome?): Boolean = try {
    tick() == null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
    Log.w("OpenWeights", "a scheduled check could not run", failure)
    false
}

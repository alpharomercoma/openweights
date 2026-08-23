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
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
        runCatching { runner.tick(id) }
        return Result.success()
    }

    companion object {
        const val WATCH_ID = "watchId"

        /** One name per watch, so scheduling twice replaces rather than doubles. */
        fun workName(watchId: Long) = "watch-$watchId"
    }
}

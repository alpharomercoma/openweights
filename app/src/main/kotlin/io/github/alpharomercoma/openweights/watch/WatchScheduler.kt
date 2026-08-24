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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.core.common.context.Watch
import io.github.alpharomercoma.openweights.core.common.context.WatchState
import io.github.alpharomercoma.openweights.core.data.WatchRepository
import io.github.alpharomercoma.openweights.runtime.GenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Keeps every active watch on a schedule, in the only two ways Android actually offers.
 *
 * ### Why there are two
 *
 * `PeriodicWorkRequest` will not repeat faster than fifteen minutes. That is
 * `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`, it is not configurable, and asking for
 * less does not fail: WorkManager rounds it up silently. A watch set to five minutes would
 * quietly run every fifteen and nothing on screen would say so, which is worse than refusing.
 *
 * So a watch at fifteen minutes or more is scheduled work and nothing is held open. A watch
 * under fifteen runs on a ticker inside the process, and the process is kept alive by the
 * same foreground service a goal uses, because a cached process is frozen and a frozen ticker
 * does not tick. That has a visible notification attached, which is the honest trade: the
 * user asked to be woken sooner than the system wakes anyone, and the notification is what
 * pays for it and what they cancel it from.
 *
 * A scheduled job is registered for the fast ones too, at the floor, as a backstop for a
 * process that dies anyway.
 */
@Singleton
class WatchScheduler @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val watches: WatchRepository,
    /**
     * Asked for at tick time rather than injected.
     *
     * A `Provider` because the graph is circular otherwise: the runner needs the turn
     * machinery, which needs the tool registry, which holds the watch tool, which needs
     * somewhere to put a watch, which is this. Deferring one edge is the ordinary way out,
     * and it is the honest one here too, since a scheduler does not need a runner until
     * something is actually due.
     */
    private val runner: Provider<WatchRunner>,
) {
    private val scope = CoroutineScope(SupervisorJob() + EmptyCoroutineContext)

    /** The in-process tickers, by watch id. Only the ones too fast for WorkManager. */
    private val tickers = mutableMapOf<Long, Job>()

    /** The cadence each ticker was started with, so a changed one can be noticed. */
    private val periods = mutableMapOf<Long, Int>()

    /** Rebuilds every schedule from what is on disk. Called at startup. */
    suspend fun sync() {
        val active = watches.active()
        active.forEach { schedule(it) }
        val live = active.map { it.id }.toSet()
        synchronized(tickers) {
            tickers.keys.filterNot { it in live }.forEach { id ->
                tickers.remove(id)?.cancel()
            }
        }
    }

    /** Starts, or restarts, the schedule for one watch. */
    fun schedule(watch: Watch) {
        if (!watch.isActive) {
            cancel(watch.id)
            return
        }
        val everyMinutes = watch.everyMinutes
            .coerceAtLeast(Watch.SCHEDULER_FLOOR_MINUTES)
            .toLong()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WatchWorker.workName(watch.id),
            // Replace rather than keep: an edited interval has to take effect, and this is
            // also the path a restart takes, where keeping would be right and replacing is
            // merely equivalent.
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WatchWorker>(everyMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(WatchWorker.WATCH_ID to watch.id))
                // Not requiresDeviceIdle, which would be the opposite of the point, and not
                // requiresBatteryNotLow either: the runner asks about the battery itself and
                // records a skip, which is more useful than a tick that silently never came.
                .setConstraints(Constraints.Builder().build())
                .build(),
        )

        if (!watch.needsForegroundService) {
            stopTicker(watch.id)
            return
        }
        startTicker(watch)
    }

    /** Stops everything for one watch. Safe to call for a watch that was never scheduled. */
    fun cancel(watchId: Long) {
        WorkManager.getInstance(appContext).cancelUniqueWork(WatchWorker.workName(watchId))
        stopTicker(watchId)
    }

    private fun startTicker(watch: Watch) {
        synchronized(tickers) {
            // A ticker already running at this cadence is left alone; one running at a
            // different cadence is replaced. Returning early regardless meant an edited
            // interval did nothing until the process restarted, which is the kind of setting
            // that looks saved and is not.
            val running = tickers[watch.id]
            if (running?.isActive == true) {
                if (periods[watch.id] == watch.everyMinutes) return
                running.cancel()
            }
            periods[watch.id] = watch.everyMinutes
            GenerationService.hold(appContext, holderFor(watch.id), "Watching")
            tickers[watch.id] = scope.launch {
                // The hold is released in this coroutine's own finally rather than by
                // whoever stops it. Every way this can end goes through here: the loop
                // breaking because the watch was stopped, a cancel from [cancel], and a
                // throw. The previous arrangement released it from `stopTicker`, which the
                // coroutine also called on its own last line, so an ordinary end released
                // twice and a cancellation released once, from a different thread, after
                // the map entry had already gone. A foreground notification left up because
                // one path missed the release is the visible half of that.
                try {
                    val period = watch.everyMinutes * MILLIS_PER_MINUTE
                    while (isActive) {
                        delay(period)
                        val current = watches.byId(watch.id)
                        if (current == null || current.state != WatchState.ACTIVE) break
                        runCatching { runner.get().tick(watch.id) }
                    }
                } finally {
                    // Only if this coroutine is still the one registered. Cancellation is
                    // asynchronous: `stopTicker` cancels and returns, and a reschedule can
                    // start a replacement before the cancelled job reaches this line. The
                    // previous version removed unconditionally, so the *old* job evicted the
                    // *new* job from the map and released the hold the new one had just
                    // taken. That left a ticker nothing could cancel, running with no
                    // foreground service, and a refcount one release too low.
                    val mine = coroutineContext[Job]
                    val wasCurrent = synchronized(tickers) {
                        if (tickers[watch.id] === mine) {
                            tickers.remove(watch.id)
                            true
                        } else {
                            false
                        }
                    }
                    if (wasCurrent) {
                        GenerationService.release(appContext, holderFor(watch.id))
                    }
                }
            }
        }
    }

    /**
     * Stops the ticker for one watch, if it has one.
     *
     * Only cancels. The hold and the map entry are the coroutine's own to release, in its
     * finally, because cancellation is asynchronous: doing it here would race the coroutine
     * that is still winding down.
     */
    private fun stopTicker(watchId: Long) {
        synchronized(tickers) { tickers[watchId] }?.cancel()
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L

        /**
         * One holder per watch, so three fast watches do not each drop the service the
         * others still need. See `GenerationService.holders`.
         */
        fun holderFor(watchId: Long) = "watch-$watchId"
    }
}

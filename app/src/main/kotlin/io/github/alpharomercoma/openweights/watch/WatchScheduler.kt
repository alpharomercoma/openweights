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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.context.Watch
import io.github.alpharomercoma.openweights.core.common.context.WatchState
import io.github.alpharomercoma.openweights.core.data.WatchRepository
import io.github.alpharomercoma.openweights.di.ApplicationScope
import io.github.alpharomercoma.openweights.runtime.GenerationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

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
    /**
     * Injected so a test can hand over a `TestScope` and run a ticker's `delay` calls in
     * virtual time instead of a real one. See [io.github.alpharomercoma.openweights.di.ApplicationScope].
     */
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** The in-process tickers, by watch id. Only the ones too fast for WorkManager. */
    private val tickers = mutableMapOf<Long, Job>()

    /** The cadence each ticker was started with, so a changed one can be noticed. */
    private val periods = mutableMapOf<Long, Int>()

    /** Rebuilds every schedule from what is on disk. Called at startup. */
    suspend fun sync() {
        // Before the schedules are rebuilt, not after: a watch whose window closed while the
        // app was shut must not be handed a new ticker and a fresh notification on its way
        // to being told it is over. Its scheduled work goes with it, since nothing else
        // will cancel a job for a watch that no longer ticks, and it is announced, because
        // a watch that ends while nobody is looking is the case most in need of saying so.
        watches.expireLapsed(System.currentTimeMillis()).forEach { ended ->
            cancel(ended.id)
            runner.get().announceEnd(ended)
        }
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
            recordScheduledDeadline(watch.id)
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
            tickers[watch.id] = scope.launch {
                // Taken here rather than before the launch, so that the hold and its release
                // are the same coroutine's. Taken outside, a ticker replaced before the
                // dispatcher had even started it — two schedules in a row, which is what
                // editing an interval is — left a hold nobody would ever give back: the
                // cancelled job never ran a line, so its `finally` never ran either, and
                // the notification stayed up for a ticker that had never existed.
                GenerationService.hold(
                    appContext,
                    holderFor(watch.id),
                    watchNotificationLabel(watch),
                )
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
                    // A restarted ticker waits a whole fresh period, so the deadline this
                    // watch inherited from the last process is wrong from here on. Written
                    // down before the first sleep, so the countdown on the screen and the
                    // one in the notification are both the alarm this loop is keeping.
                    watches.reschedule(watch.id, System.currentTimeMillis())
                    watches.byId(watch.id)?.let(::showProgress)
                    var live = true
                    while (isActive && live) {
                        delay(period)
                        val current = watches.byId(watch.id)
                        // Short-circuit order is the contract: a watch that is gone or
                        // paused must end the loop without ticking at all.
                        live = current != null &&
                            current.state == WatchState.ACTIVE &&
                            tickOnce(watch.id)
                        // After the tick rather than before it: the counter has just moved
                        // and the next deadline is a period from now. Read back rather than
                        // computed here, because a tick that was skipped did not move
                        // either, and the notification should say what happened.
                        if (live) watches.byId(watch.id)?.let(::showProgress)
                    }
                } finally {
                    // The map entry only if this coroutine is still the one registered.
                    // Cancellation is asynchronous: `stopTicker` cancels and returns, and a
                    // reschedule can start a replacement before the cancelled job reaches
                    // this line. Removing unconditionally let the *old* job evict the *new*
                    // one, leaving a ticker nothing could cancel.
                    val mine = coroutineContext[Job]
                    synchronized(tickers) {
                        if (tickers[watch.id] === mine) tickers.remove(watch.id)
                    }
                    // The hold, always. It was taken for this coroutine and is this
                    // coroutine's to give back — the count exists precisely so a
                    // replacement's hold can overlap this one's release. Releasing only
                    // when this job was still the registered one meant an edited interval
                    // leaked a hold every time it was edited: the old job saw itself
                    // replaced, released nothing, and the notification stayed up for a
                    // ticker that had already finished.
                    GenerationService.release(appContext, holderFor(watch.id))
                }
            }
        }
    }

    /**
     * Asks `WorkManager` when it will next run this watch, and writes that down.
     *
     * Asked rather than assumed, because the app is not the one keeping this schedule. The
     * enqueue policy is UPDATE, which keeps an existing period's timing where it can, so a
     * watch rescheduled at every startup would have its countdown pushed forward by a whole
     * period each time the app opened while the job itself never moved. `WorkInfo` knows the
     * real answer; a screen that says "next in 12m" should be repeating it rather than
     * guessing alongside it.
     */
    private fun recordScheduledDeadline(watchId: Long) {
        scope.launch {
            // Bounded, because this is a flow of somebody else's state and it is read once
            // per schedule: a query that never emitted would leave a coroutine waiting for
            // the life of the process, and there are four of these per app start.
            val next = runCatching {
                withTimeoutOrNull(WORK_INFO_TIMEOUT_MS) {
                    WorkManager.getInstance(appContext)
                        .getWorkInfosForUniqueWorkFlow(WatchWorker.workName(watchId))
                        .first()
                        .firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                        ?.nextScheduleTimeMillis
                }
            }.getOrNull()
            // Long.MAX_VALUE is what WorkInfo reports for work that is not scheduled to run
            // again, and a countdown to the end of time is worse than none.
            if (next != null && next > 0 && next != Long.MAX_VALUE) {
                watches.rescheduleAt(watchId, next)
            }
        }
    }

    /**
     * Puts this watch's count and its next deadline on the notification it is keeping up.
     *
     * Only for the fast watches, because they are the only ones that hold the service. A
     * scheduled watch has no notification between ticks by design, and its count and
     * countdown are on the Watching screen instead.
     *
     * The deadline goes over as a timestamp so the system can draw the countdown itself.
     * See `GenerationService.notification`.
     */
    private fun showProgress(watch: Watch, now: Long = System.currentTimeMillis()) {
        if (!watch.needsForegroundService || !watch.isActive) return
        val due = watch.dueAt
        GenerationService.relabel(
            context = appContext,
            holder = holderFor(watch.id),
            label = watchNotificationLabel(watch),
            detail = appContext.getString(
                R.string.watch_notification_progress,
                watch.runs,
                Watch.MAX_RUNS,
            ),
            // Only while the moment is still ahead. Android's countdown chronometer has no
            // idea of "overdue": handed a time that has passed it counts upward away from
            // zero, which reads as a stopwatch of how late the app is. A check that is owed
            // says so in words instead.
            countdownTo = due.takeIf { it > now },
        )
    }

    /**
     * One tick, and whether the watch should keep running afterwards.
     *
     * One bad tick must not end the loop, and a cancellation must; `runCatching` alone
     * caught both and told nobody about either. Null is [WatchRunner.tick]'s own signal
     * that recording this tick was what stopped the watch — the third failure, or a pause
     * that landed between the caller's read and this call. Acted on immediately rather
     * than left for the top of the loop, which would have delayed a full period first: a
     * notification and a foreground hold the watch no longer needs, kept for up to
     * fourteen more minutes for no reason other than not having looked yet.
     */
    private suspend fun tickOnce(watchId: Long): Boolean = try {
        runner.get().tick(watchId) != null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        Log.w("OpenWeights", "watch $watchId could not run", failure)
        true
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

        /** Long enough for a local query, short enough that a stuck one is not forever. */
        const val WORK_INFO_TIMEOUT_MS = 5_000L
        const val MAX_NOTIFICATION_TASK_CHARS = 60

        /**
         * One holder per watch, so three fast watches do not each drop the service the
         * others still need. See `GenerationService.holders`.
         */
        fun holderFor(watchId: Long) = "watch-$watchId"

        /**
         * What the notification says while a fast watch is between ticks.
         *
         * Named by the check itself, not just "Watching": three watches running at once
         * would otherwise show three identical notifications with nothing to tell them
         * apart, and the one thing a person watching this notification wants to know is
         * which check it is.
         */
        fun watchNotificationLabel(watch: Watch): String {
            val task = watch.task.take(MAX_NOTIFICATION_TASK_CHARS)
            val truncated = if (task.length < watch.task.length) "$task…" else task
            return "Watching: $truncated"
        }
    }
}

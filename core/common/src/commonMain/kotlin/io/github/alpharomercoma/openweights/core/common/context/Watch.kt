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

package io.github.alpharomercoma.openweights.core.common.context

/**
 * Something to check again, on a schedule, until told to stop.
 *
 * Called a watch rather than a monitor only in the code; the interface says "Watching" and
 * the model's tool is `watch`. Claude Code calls the same capability a Monitor, and the
 * distinction it draws is the useful one: a *background task* ends when its condition first
 * becomes true and notifies once, while a *monitor* keeps emitting until it is stopped. This
 * is the second kind. A goal, which runs a plan to completion and ends, is the first.
 *
 * ### What the schedule can actually promise
 *
 * Android will not wake an app on an arbitrary cadence, and pretending otherwise is how this
 * feature would become undependable. `WorkManager`'s periodic floor is fifteen minutes, and
 * Doze can stretch even that. So there are two regimes and the interface has to be honest
 * about which one a watch is in:
 *
 * - **[everyMinutes] of 15 or more**: scheduled work. Nothing is held open and the tick may
 *   arrive late. It does **not** mean the check runs with the app closed: a scheduled tick
 *   that wakes a fresh process finds no model loaded, and `WatchRunner` deliberately records
 *   a skip rather than opening two gigabytes of weights in the background for a question
 *   nobody is waiting for. So a watch on a killed app resumes at the first tick after the
 *   app is next opened. The earlier wording here promised autonomy the runner never had.
 * - **Under 15**: the app keeps a foreground notification up for as long as the watch is
 *   active, because that is the only way to be woken sooner, and the user can see and cancel
 *   it. A scheduled job is registered alongside as a backstop for a process that dies.
 *
 * ### Guardrails
 *
 * A thing that runs on its own on a phone needs more of these than a thing a person is
 * waiting for. [MAX_ACTIVE] bounds how many can exist; [MAX_CONSECUTIVE_FAILURES] stops one
 * that is only producing errors; the runner refuses to start a tick while the engine is busy
 * rather than queueing, because a queue of stale checks is worse than a missed one.
 */
data class Watch(
    val id: Long = 0,
    /** What to check, in the user's words. Sent as the prompt for every tick. */
    val task: String,
    val everyMinutes: Int,
    val state: WatchState = WatchState.ACTIVE,
    val createdAt: Long = 0,
    /** When the last tick ran, or null before the first. */
    val lastRunAt: Long? = null,
    /** What the last tick concluded, one line, or null before the first. */
    val lastSummary: String? = null,
    val runs: Int = 0,
    /** Reset by any tick that succeeds. See [MAX_CONSECUTIVE_FAILURES]. */
    val consecutiveFailures: Int = 0,
) {
    init {
        require(task.isNotBlank()) { "a watch needs something to check" }
        require(everyMinutes in MIN_MINUTES..MAX_MINUTES) {
            "everyMinutes must be within $MIN_MINUTES..$MAX_MINUTES"
        }
    }

    /** True when this cadence is faster than Android will schedule unaided. */
    val needsForegroundService: Boolean get() = everyMinutes < SCHEDULER_FLOOR_MINUTES

    val isActive: Boolean get() = state == WatchState.ACTIVE

    companion object {
        /**
         * One minute, which is as fast as this is worth offering.
         *
         * Every tick is a full turn of a language model on a phone, and a turn takes tens of
         * seconds. Faster than this and the next tick is due before the last one finished.
         */
        const val MIN_MINUTES = 1

        /** A day. Past this, a watch is a reminder and the phone has one of those. */
        const val MAX_MINUTES = 1_440

        /**
         * `WorkManager.PeriodicWorkRequest`'s own floor, and not a choice this app makes.
         *
         * `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is fifteen minutes. Asking for
         * less does not fail, it silently rounds up, which is the worst of both: a watch the
         * user set to five minutes would quietly run every fifteen and nothing would say so.
         */
        const val SCHEDULER_FLOOR_MINUTES = 15

        /**
         * How many can be active at once.
         *
         * Four, because each one is a model turn and they contend for one engine. The limit
         * is about the phone rather than about the storage.
         */
        const val MAX_ACTIVE = 4

        /**
         * Ticks in a row that may fail before the watch stops itself.
         *
         * Three. A watch that cannot do its job is not going to start working because it is
         * asked another two hundred times, and the cost of finding out is battery.
         */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}

/** Where a watch is in its life. */
enum class WatchState {
    /** Scheduled and ticking. */
    ACTIVE,

    /** Stopped by the user, and kept so the history is still readable. */
    STOPPED,

    /** Stopped by the app after [Watch.MAX_CONSECUTIVE_FAILURES] failures in a row. */
    FAILED,
}

/** One tick, and what came of it. */
data class WatchRun(
    val id: Long = 0,
    val watchId: Long,
    val at: Long,
    val outcome: WatchOutcome,
    /** What the model concluded, or why the tick did not happen. */
    val summary: String,
)

/** What happened when a watch came due. */
enum class WatchOutcome {
    /** The check ran and the model answered. */
    CHECKED,

    /**
     * The check was due and did not run, and the watch is still healthy.
     *
     * Skipped rather than queued on purpose. The engine holds one model and one context, so
     * a tick that arrives while the user is mid-conversation cannot run without either
     * waiting behind them or corrupting the turn. Waiting means a backlog of checks whose
     * moment has passed, all of which then run at once; a phone that is too hot or nearly
     * flat has the same answer. A missed check is the correct outcome, and saying it was
     * missed is what makes the record trustworthy.
     */
    SKIPPED,

    /** The check ran and failed. See [Watch.MAX_CONSECUTIVE_FAILURES]. */
    FAILED,
}

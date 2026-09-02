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
 *
 * And no watch runs forever. [MAX_RUNS] and [MAX_LIFETIME_HOURS] give every one of them an
 * end from the moment it is made — see [expiresAt] — because "until you stop it" is a
 * promise the user has to remember to collect on, and the one thing they cannot see from
 * outside is how much of their battery it has spent. [nextRunAt] and [runs] are the other
 * half of the same argument: a watch should be able to say when it will look again and how
 * many times it already has, on screen and in its notification, rather than only that it
 * exists.
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
    /**
     * When the next check is due, as whatever scheduled it last worked it out.
     *
     * Stored rather than worked out from the last run and the interval, and that is a
     * correction rather than a preference. Derived, it was wrong in every case that
     * matters: a tick skipped for a busy engine leaves [lastRunAt] alone but still waits a
     * whole period, so the derived moment sat in the past while nothing was owed; a ticker
     * restarted with the process begins a fresh period from now rather than from whenever
     * the last run happened; and an edited interval recomputed history as though the new
     * cadence had always been in force. Whoever sets the alarm writes it down here, so the
     * countdown on screen is the alarm rather than a guess at it.
     *
     * Null before anything has scheduled it, where [dueAt] falls back to the interval from
     * when the watch was made.
     */
    val nextRunAt: Long? = null,
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

    /**
     * When the next check is due, which is never a promise that it will run then.
     *
     * Doze stretches a scheduled tick, and a fast watch whose engine is busy records a skip
     * and waits for the next period. This is what is owed, not what will happen, and the
     * interface says so.
     */
    val dueAt: Long get() = nextRunAt ?: (createdAt + everyMinutes * MILLIS_PER_MINUTE)

    /**
     * When this watch stops itself for having existed long enough.
     *
     * Wall clock alone. The first version of this took the sooner of the clock and the
     * budget spent at full speed, which reads as prudent and is simply wrong: a budget is
     * spent in *checks*, each of which takes tens of seconds, and a skipped tick spends
     * none of it. Turning it into a deadline guaranteed a watch stopped before running the
     * number of checks it had just promised, and at a daily cadence guaranteed it ran none
     * at all. The two bounds are independent, and each is honest on its own terms.
     */
    val expiresAt: Long get() = createdAt + MAX_LIFETIME_HOURS * MILLIS_PER_HOUR

    /** True once this watch has spent its budget or outlived its window. */
    fun isSpent(now: Long): Boolean = runs >= MAX_RUNS || now >= expiresAt

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

        /**
         * Checks one watch may run before it stops itself.
         *
         * A watch used to run until it was stopped by hand, and nothing on screen said so.
         * That is the wrong default for work nobody is watching: every check is a full model
         * turn, and a one-minute watch forgotten overnight is some seven hundred of them
         * against a battery its owner is asleep next to. Sixty is enough for a watch to be
         * useful — an hour at the fastest cadence, two days at an hourly one — and small
         * enough that forgetting one costs an hour rather than a night.
         */
        const val MAX_RUNS = 60

        /**
         * The longest a watch may exist, whatever its cadence.
         *
         * The budget alone leaves a slow watch effectively unbounded: sixty daily checks is
         * two months.
         *
         * Three days rather than a neater one or two, and the reason is arithmetic rather
         * than taste. The slowest cadence offered is a day, and the window has to be long
         * enough for the slowest watch to be worth having made: at twenty-four hours a
         * daily watch expires at the very moment its first check falls due, and at
         * forty-eight the second check lands exactly on the deadline and is refused. Since
         * a tick is allowed to be late — `WorkManager` guarantees only "eventually" — the
         * window has to clear the second check with room to spare, and seventy-two hours
         * is the first round number that does.
         */
        const val MAX_LIFETIME_HOURS = 72

        internal const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_HOUR = 3_600_000L
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

    /**
     * Stopped by the app because it ran out of budget or outlived its window.
     *
     * Its own state rather than [STOPPED], because the two answer different questions from
     * the user's side: one is "I ended this" and the other is "this ended on its own, and
     * here is how much it did first".
     */
    EXPIRED,
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

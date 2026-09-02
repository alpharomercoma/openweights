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

import androidx.room.withTransaction
import io.github.alpharomercoma.openweights.core.common.context.Watch
import io.github.alpharomercoma.openweights.core.common.context.WatchOutcome
import io.github.alpharomercoma.openweights.core.common.context.WatchRun
import io.github.alpharomercoma.openweights.core.common.context.WatchState
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.data.db.WatchEntity
import io.github.alpharomercoma.openweights.core.data.db.WatchRunEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where watches live between ticks, and between one run of the app and the next.
 *
 * On disk rather than in memory, because the whole promise of a watch is that it keeps going
 * when nobody is looking, and a process Android reclaims takes any in-memory schedule with
 * it. The scheduler is rebuilt from [active] at startup for exactly this reason.
 */
@Singleton
class WatchRepository @Inject constructor(private val database: OpenWeightsDatabase) {
    fun observeAll(): Flow<List<Watch>> =
        database.watches().observeAll().map { rows -> rows.map { it.asWatch() } }

    fun observeRuns(watchId: Long, limit: Int = RUN_HISTORY): Flow<List<WatchRun>> =
        database.watchRuns().observeRuns(watchId, limit).map { rows -> rows.map { it.asRun() } }

    suspend fun active(): List<Watch> =
        database.watches().inState(WatchState.ACTIVE.name).map { it.asWatch() }

    suspend fun byId(id: Long): Watch? = database.watches().byId(id)?.asWatch()

    /**
     * Starts watching, or says why not.
     *
     * Returns null when there are already [Watch.MAX_ACTIVE] running. Refused here rather
     * than in the interface so that the model's tool and the user's command cannot disagree
     * about the limit, and so a tool call cannot talk its way past it.
     */
    suspend fun add(task: String, everyMinutes: Int, now: Long): Watch? = database.withTransaction {
        // The rows rather than a count of them: there are at most four, and one statement
        // that answers both "how many" and "which ones" is one fewer to keep in step. Counted
        // and inserted in one transaction, the way record() already is, so two adds that
        // land together cannot both see three and make five.
        if (database.watches().inState(WatchState.ACTIVE.name).size >= Watch.MAX_ACTIVE) {
            return@withTransaction null
        }
        val watch = Watch(
            task = task.trim(),
            everyMinutes = everyMinutes,
            createdAt = now,
            nextRunAt = now + everyMinutes * MILLIS_PER_MINUTE,
        )
        val id = database.watches().insert(watch.asEntity())
        watch.copy(id = id)
    }

    /**
     * Ends every active watch that has outlived its window, and says how many.
     *
     * A watch normally expires in the write that counts its last check, but the clock half
     * of the bound does not need a check to pass: a watch set every hour, left with the app
     * closed for two days, is over long before anything ticks. Swept at startup, where the
     * schedules are rebuilt, so a lapsed watch is never re-scheduled.
     */
    suspend fun expireLapsed(now: Long): List<Watch> = database.withTransaction {
        val lapsed = database.watches().inState(WatchState.ACTIVE.name)
            .map { it.asWatch() }
            .filter { it.isSpent(now) }
        lapsed.forEach { stop(it.id, WatchState.EXPIRED) }
        lapsed.map { it.copy(state = WatchState.EXPIRED) }
    }

    /**
     * Moves the next deadline to one period from [from], for an active watch.
     *
     * Called by whatever has just set the alarm — a ticker starting its first sleep, a
     * scheduled job being enqueued — because only that code knows when the period actually
     * begins. A stopped watch is left alone: a countdown on something that has ended is
     * worse than none.
     */
    suspend fun reschedule(id: Long, from: Long) {
        val existing = database.watches().byId(id) ?: return
        database.watches().setNextRunAt(
            id = id,
            at = from + existing.everyMinutes * MILLIS_PER_MINUTE,
        )
    }

    /** Records a deadline someone else owns — `WorkManager`'s own answer for a slow watch. */
    suspend fun rescheduleAt(id: Long, at: Long) {
        database.watches().setNextRunAt(id, at)
    }

    /**
     * Stops a watch and keeps it, so what it found is still readable.
     *
     * Only an active watch. Without that guard the startup sweep could overwrite a watch
     * that had already stopped itself after three failures, and the screen would say it
     * ran out of time when in fact it broke — the two readings lead a person to do
     * different things next.
     */
    suspend fun stop(id: Long, state: WatchState = WatchState.STOPPED) {
        database.watches().endIfActive(id, state.name)
    }

    /** Removes a watch and, by the foreign key, everything it recorded. */
    suspend fun forget(id: Long) = database.watches().delete(id)

    /**
     * Writes down what one tick did and moves the watch's counters on.
     *
     * The failure counter is the guardrail: it rises on a failure and resets on anything
     * else, so three failures *in a row* stop the watch while three spread over a day do
     * not. A skipped tick is neither, since nothing was attempted.
     */
    suspend fun record(watchId: Long, at: Long, outcome: WatchOutcome, summary: String): Watch? =
        // `database.withTransaction`, not `@Transaction`. The annotation only does anything
        // on a DAO method: Room generates the wrapper as part of the DAO implementation, and
        // on a repository function it compiles, reads like a transaction, and does nothing
        // at all. The first attempt at this fix was exactly that, so the interleaving it was
        // written to close stayed open.
        database.withTransaction {
            recordInTransaction(watchId, at, outcome, summary)
        }

    /**
     * The body of [record], which must not be called outside its transaction.
     *
     * Read, insert, trim, count and write, and every one of those was a separate statement
     * before. Two ticks can overlap: the in-process ticker and the fifteen-minute WorkManager
     * backstop both reach here, and the tick loop is only serialised at the engine, not at
     * this table. One tick recording the third failure could mark the watch FAILED, and a
     * second tick that had already read the ACTIVE row could then write ACTIVE back over it,
     * undoing the guardrail that exists to stop a broken watch running forever.
     *
     * A transaction also fixes the smaller half: a process death between the history insert
     * and the counter write left a run recorded that no counter had counted.
     */
    private suspend fun recordInTransaction(
        watchId: Long,
        at: Long,
        outcome: WatchOutcome,
        summary: String,
    ): Watch? {
        val existing = database.watches().byId(watchId) ?: return null
        // Re-read inside the transaction is what makes the failure counter safe; a watch
        // another tick has already stopped must not be revived by this one.
        if (existing.state != WatchState.ACTIVE.name) return existing.asWatch()
        database.watchRuns().insertRun(
            WatchRunEntity(
                watchId = watchId,
                at = at,
                outcome = outcome.name,
                summary = summary.take(SUMMARY_CHARS),
            ),
        )
        database.watchRuns().trimRuns(watchId, RUN_HISTORY)

        val failures = when (outcome) {
            WatchOutcome.FAILED -> existing.consecutiveFailures + 1
            WatchOutcome.CHECKED -> 0
            WatchOutcome.SKIPPED -> existing.consecutiveFailures
        }
        val exhausted = failures >= Watch.MAX_CONSECUTIVE_FAILURES
        val counted = existing.copy(
            lastRunAt = if (outcome == WatchOutcome.SKIPPED) existing.lastRunAt else at,
            // A skip's reason goes to the run history, not here: "Skipped at 12% battery"
            // replacing what the last real check found would blank the one line on the
            // screen worth reading, and the previous finding is also what the next check
            // is asked to compare against.
            lastSummary = if (outcome == WatchOutcome.SKIPPED) {
                existing.lastSummary
            } else {
                summary.take(SUMMARY_CHARS)
            },
            runs = if (outcome == WatchOutcome.SKIPPED) existing.runs else existing.runs + 1,
            consecutiveFailures = failures,
            // A skipped tick moves the deadline too. It is the attempt that resets the
            // clock, not the check: the ticker sleeps another full period either way, and a
            // countdown left pointing at a moment that has already passed is the thing that
            // made the old screen feel broken.
            nextRunAt = at + existing.everyMinutes * MILLIS_PER_MINUTE,
        )
        // Spending the last check of the budget ends the watch in the same write that
        // counted it. Deciding this a moment later, from the ticker, would leave a window
        // where the row says a watch is active with nothing left to do — and the ticker is
        // exactly what a process death takes away.
        val updated = counted.copy(
            state = when {
                exhausted -> WatchState.FAILED.name
                counted.asWatch().isSpent(at) -> WatchState.EXPIRED.name
                else -> existing.state
            },
        )
        database.watches().upsert(updated)
        return updated.asWatch()
    }

    private companion object {
        /**
         * How many ticks of one watch are kept.
         *
         * Twenty, which is a screen of history and, at the fastest cadence, twenty minutes
         * of it. The point of the log is to answer "what has it been finding", not to be an
         * archive.
         */
        const val RUN_HISTORY = 20

        /** One line. A watch that writes an essay every minute is a database, not a check. */
        const val SUMMARY_CHARS = 400

        const val MILLIS_PER_MINUTE = 60_000L
    }
}

private fun WatchEntity.asWatch() = Watch(
    id = id,
    task = task,
    everyMinutes = everyMinutes,
    state = runCatching { WatchState.valueOf(state) }.getOrDefault(WatchState.STOPPED),
    createdAt = createdAt,
    lastRunAt = lastRunAt,
    lastSummary = lastSummary,
    runs = runs,
    consecutiveFailures = consecutiveFailures,
    nextRunAt = nextRunAt,
)

private fun Watch.asEntity() = WatchEntity(
    id = id,
    task = task,
    everyMinutes = everyMinutes,
    state = state.name,
    createdAt = createdAt,
    lastRunAt = lastRunAt,
    lastSummary = lastSummary,
    runs = runs,
    consecutiveFailures = consecutiveFailures,
    nextRunAt = nextRunAt,
)

private fun WatchRunEntity.asRun() = WatchRun(
    id = id,
    watchId = watchId,
    at = at,
    outcome = runCatching { WatchOutcome.valueOf(outcome) }.getOrDefault(WatchOutcome.FAILED),
    summary = summary,
)

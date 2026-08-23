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
        database.watches().observeRuns(watchId, limit).map { rows -> rows.map { it.asRun() } }

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
    suspend fun add(task: String, everyMinutes: Int, now: Long): Watch? {
        if (database.watches().countInState(WatchState.ACTIVE.name) >= Watch.MAX_ACTIVE) {
            return null
        }
        val watch = Watch(task = task.trim(), everyMinutes = everyMinutes, createdAt = now)
        val id = database.watches().insert(watch.asEntity())
        return watch.copy(id = id)
    }

    /** Stops a watch and keeps it, so what it found is still readable. */
    suspend fun stop(id: Long, state: WatchState = WatchState.STOPPED) {
        val existing = database.watches().byId(id) ?: return
        database.watches().upsert(existing.copy(state = state.name))
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
    suspend fun record(watchId: Long, at: Long, outcome: WatchOutcome, summary: String): Watch? {
        val existing = database.watches().byId(watchId) ?: return null
        database.watches().insertRun(
            WatchRunEntity(
                watchId = watchId,
                at = at,
                outcome = outcome.name,
                summary = summary.take(SUMMARY_CHARS),
            ),
        )
        database.watches().trimRuns(watchId, RUN_HISTORY)

        val failures = when (outcome) {
            WatchOutcome.FAILED -> existing.consecutiveFailures + 1
            WatchOutcome.CHECKED -> 0
            WatchOutcome.SKIPPED -> existing.consecutiveFailures
        }
        val exhausted = failures >= Watch.MAX_CONSECUTIVE_FAILURES
        val updated = existing.copy(
            state = if (exhausted) WatchState.FAILED.name else existing.state,
            lastRunAt = if (outcome == WatchOutcome.SKIPPED) existing.lastRunAt else at,
            lastSummary = summary.take(SUMMARY_CHARS),
            runs = if (outcome == WatchOutcome.SKIPPED) existing.runs else existing.runs + 1,
            consecutiveFailures = failures,
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
)

private fun WatchRunEntity.asRun() = WatchRun(
    id = id,
    watchId = watchId,
    at = at,
    outcome = runCatching { WatchOutcome.valueOf(outcome) }.getOrDefault(WatchOutcome.FAILED),
    summary = summary,
)

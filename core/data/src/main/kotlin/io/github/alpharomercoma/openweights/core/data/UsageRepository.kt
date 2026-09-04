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

import io.github.alpharomercoma.openweights.core.data.db.ModelDecodeSpeed
import io.github.alpharomercoma.openweights.core.data.db.ModelPrefillSpeed
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.data.db.UsageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Tokens generated on one day. */
data class DailyUsage(val day: Long, val generatedTokens: Long)

/**
 * One day on the lifetime curve.
 *
 * @param day days since the epoch, local time.
 * @param dayTokens what was generated that day, zero on a day the app was not used.
 * @param cumulativeTokens the lifetime total as it stood at the end of that day.
 */
data class GrowthPoint(val day: Long, val dayTokens: Long, val cumulativeTokens: Long)

/** What one model has been used for, over its lifetime on this device. */
data class ModelUsage(
    val modelName: String,
    val generatedTokens: Long,
    val replies: Int,
    val averageTokensPerSecond: Double?,
    /**
     * Decode-only throughput, from the decode columns alone.
     *
     * [averageTokensPerSecond] divides by prefill time as well and is the honest "how much
     * of the day did this cost" number; this one is the model's own writing speed, which
     * is what a developer comparing engines actually wants on the row. Null until a reply
     * has been recorded by a version that measured the split.
     */
    val decodeTokensPerSecond: Double? = null,
    /** Prompt-reading throughput, the prefill mirror of [decodeTokensPerSecond]. */
    val prefillTokensPerSecond: Double? = null,
    /**
     * The runtime the model runs on, when it is not the default one.
     *
     * Filled in by the dashboard from what is installed, not stored: the ledger predates
     * second runtimes and a usage row is about work done, not about engines. Null for
     * llama.cpp, which is the unmarked case.
     */
    val runtime: String? = null,
)

/** Everything the dashboard shows. All of it derived from the local ledger. */
data class UsageSummary(
    val lifetimePromptTokens: Long = 0,
    val lifetimeGeneratedTokens: Long = 0,
    val lifetimeInferenceMs: Long = 0,
    val lifetimeDecodeMs: Long = 0,
    val lifetimeDecodeTokens: Long = 0,
    val lifetimePrefillMs: Long = 0,
    val lifetimePrefillTokens: Long = 0,
    val replies: Int = 0,
    val conversations: Int = 0,
    val activeDays: Int = 0,
    val perDay: List<DailyUsage> = emptyList(),
    val perModel: List<ModelUsage> = emptyList(),
    /** The lifetime total day by day, gaps filled, most recent last. */
    val growth: List<GrowthPoint> = emptyList(),
) {
    /** What was generated on the most recent day in the window. */
    val tokensToday: Long get() = growth.lastOrNull()?.dayTokens ?: 0

    /** The day before it, which is what today is being compared against. */
    val tokensYesterday: Long get() = growth.getOrNull(growth.lastIndex - 1)?.dayTokens ?: 0

    /**
     * Today against yesterday, as a fraction.
     *
     * Null when yesterday was zero: everything is infinitely more than nothing, and a
     * percentage saying so tells the reader less than the raw count beside it already has.
     */
    val dayOverDayChange: Double?
        get() = if (tokensYesterday > 0) {
            (tokensToday - tokensYesterday).toDouble() / tokensYesterday
        } else {
            null
        }

    /**
     * Lifetime decode-only speed, and its prefill mirror.
     *
     * The only two speeds this screen shows. There was a third, generated tokens over total
     * inference time, and it answered neither question a developer brings here: prefill is
     * bound by compute and scales with the prompt, decode is bound by memory bandwidth and
     * scales with the reply, and blending them produces a figure that moves when
     * conversation habits do rather than when anything about the phone or the model does.
     * Null until a reply has been recorded with the split measured.
     */
    val decodeTokensPerSecond: Double? = rate(lifetimeDecodeTokens, lifetimeDecodeMs)
    val prefillTokensPerSecond: Double? = rate(lifetimePrefillTokens, lifetimePrefillMs)
}

private fun rate(tokens: Long, millis: Long): Double? =
    if (millis > 0 && tokens > 0) tokens * MILLIS_PER_SECOND_TOP / millis else null

/** File level, because [UsageSummary]'s computed rates run before its companion exists. */
private const val MILLIS_PER_SECOND_TOP = 1000.0

/**
 * Reads the usage ledger.
 *
 * Everything here is arithmetic over rows this device wrote. There is no analytics service
 * behind it, which is the point: the numbers exist because they are useful to the person
 * who generated them, not to anyone else.
 */
@Singleton
class UsageRepository @Inject constructor(
    private val database: OpenWeightsDatabase,
    private val clock: Clock,
) {
    fun observeSummary(): Flow<UsageSummary> = combine(
        database.usage().observeAll(),
        database.conversations().observeCount(),
    ) { rows, conversationCount ->
        rows.toSummary(conversationCount)
    }

    /**
     * Real, measured, decode-only throughput per model on this device, heaviest-used first.
     *
     * Deliberately not [observeSummary]'s [ModelUsage.averageTokensPerSecond]: that one
     * divides by prefill time as well as decode time, which is right for "how much of the
     * day did this model cost" and wrong for predicting a different model's decode speed,
     * since prefill scales with how long the prompt happened to be rather than with
     * anything about the model. See [UsageDao.decodeSpeedByModel].
     */
    suspend fun decodeSpeedByModel(): List<ModelDecodeSpeed> = database.usage().decodeSpeedByModel()

    /**
     * Real, measured, prompt-processing-only throughput per model on this device, the
     * prefill mirror of [decodeSpeedByModel]. See [UsageDao.prefillSpeedByModel].
     */
    suspend fun prefillSpeedByModel(): List<ModelPrefillSpeed> =
        database.usage().prefillSpeedByModel()

    internal fun List<UsageEntity>.toSummary(conversationCount: Int) = UsageSummary(
        lifetimePromptTokens = sumOf { it.promptTokens },
        lifetimeGeneratedTokens = sumOf { it.generatedTokens },
        lifetimeInferenceMs = sumOf { it.inferenceMs },
        lifetimeDecodeMs = sumOf { it.decodeMs },
        lifetimeDecodeTokens = sumOf { it.decodeTokens },
        lifetimePrefillMs = sumOf { it.prefillMs },
        lifetimePrefillTokens = sumOf { it.prefillTokens },
        replies = sumOf { it.replies },
        conversations = conversationCount,
        activeDays = distinctBy { it.day }.size,
        perDay = groupBy { it.day }
            .map { (day, entries) -> DailyUsage(day, entries.sumOf { it.generatedTokens }) }
            .sortedBy { it.day },
        perModel = groupBy { it.modelName }
            .map { (model, entries) ->
                val tokens = entries.sumOf { it.generatedTokens }
                val millis = entries.sumOf { it.inferenceMs }
                ModelUsage(
                    modelName = model,
                    generatedTokens = tokens,
                    replies = entries.sumOf { it.replies },
                    averageTokensPerSecond = if (millis > 0 && tokens > 0) {
                        tokens * MILLIS_PER_SECOND / millis
                    } else {
                        null
                    },
                    // Sums over rows, not averages of averages: a model with one long
                    // reply and one short must not count them equally. Rows written
                    // before the split existed hold zero in all four columns, so they
                    // drop out of the rate on their own.
                    decodeTokensPerSecond = rate(
                        entries.sumOf { it.decodeTokens },
                        entries.sumOf { it.decodeMs },
                    ),
                    prefillTokensPerSecond = rate(
                        entries.sumOf { it.prefillTokens },
                        entries.sumOf { it.prefillMs },
                    ),
                )
            }
            .sortedByDescending { it.generatedTokens },
        growth = groupBy { it.day }
            .map { (day, entries) -> DailyUsage(day, entries.sumOf { it.generatedTokens }) }
            .growth(today = clock.today()),
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

/**
 * Turns the days that have rows into a continuous lifetime curve.
 *
 * Two things the raw rows cannot show. Days with no use have no row, so plotting them in
 * the order they arrive puts a week away and a week of daily use side by side at the same
 * width, and a curve that lies about time is worse than no curve. And the lifetime total
 * only ever climbs, which is the shape worth seeing: the per-day bars say how much on a
 * day, this says how far in total.
 *
 * The window ends at [today] rather than at the last day used, so a quiet fortnight reads
 * as a flat line rather than as a chart that stops.
 *
 * @param windowDays how many days the curve covers, counting back from [today].
 */
internal fun List<DailyUsage>.growth(
    today: Long,
    windowDays: Int = GROWTH_WINDOW_DAYS,
): List<GrowthPoint> {
    if (isEmpty()) return emptyList()

    val byDay = associate { it.day to it.generatedTokens }
    val lastDay = maxOf(today, byDay.keys.max())
    val firstDay = maxOf(byDay.keys.min(), lastDay - windowDays + 1)

    // Anything older than the window is already history, so the curve starts from that
    // total rather than from zero. Starting at zero would draw a device's second month as
    // though it were its first.
    var running = filter { it.day < firstDay }.sumOf { it.generatedTokens }

    return (firstDay..lastDay).map { day ->
        val tokens = byDay[day] ?: 0
        running += tokens
        GrowthPoint(day = day, dayTokens = tokens, cumulativeTokens = running)
    }
}

/**
 * How far back the lifetime curve reaches.
 *
 * A month fits a phone's width at a readable point spacing. Past that the curve stops
 * being a shape and becomes a texture.
 */
private const val GROWTH_WINDOW_DAYS = 30

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

import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.data.db.UsageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Tokens generated on one day. */
data class DailyUsage(val day: Long, val generatedTokens: Long)

/** What one model has been used for, over its lifetime on this device. */
data class ModelUsage(
    val modelName: String,
    val generatedTokens: Long,
    val replies: Int,
    val averageTokensPerSecond: Double?,
)

/** Everything the dashboard shows. All of it derived from the local ledger. */
data class UsageSummary(
    val lifetimePromptTokens: Long = 0,
    val lifetimeGeneratedTokens: Long = 0,
    val lifetimeInferenceMs: Long = 0,
    val replies: Int = 0,
    val conversations: Int = 0,
    val activeDays: Int = 0,
    val perDay: List<DailyUsage> = emptyList(),
    val perModel: List<ModelUsage> = emptyList(),
) {
    /** Average generation speed across everything ever run here. */
    val averageTokensPerSecond: Double?
        get() = if (lifetimeInferenceMs > 0 && lifetimeGeneratedTokens > 0) {
            lifetimeGeneratedTokens * MILLIS_PER_SECOND / lifetimeInferenceMs
        } else {
            null
        }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

/**
 * Reads the usage ledger.
 *
 * Everything here is arithmetic over rows this device wrote. There is no analytics service
 * behind it, which is the point: the numbers exist because they are useful to the person
 * who generated them, not to anyone else.
 */
@Singleton
class UsageRepository @Inject constructor(private val database: OpenWeightsDatabase) {
    fun observeSummary(): Flow<UsageSummary> = combine(
        database.usage().observeAll(),
        database.conversations().observeCount(),
    ) { rows, conversationCount ->
        rows.toSummary(conversationCount)
    }

    internal fun List<UsageEntity>.toSummary(conversationCount: Int) = UsageSummary(
        lifetimePromptTokens = sumOf { it.promptTokens },
        lifetimeGeneratedTokens = sumOf { it.generatedTokens },
        lifetimeInferenceMs = sumOf { it.inferenceMs },
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
                )
            }
            .sortedByDescending { it.generatedTokens },
    )

    fun observeDaily(): Flow<List<DailyUsage>> = database.usage().observeAll().map { rows ->
        rows.groupBy { it.day }
            .map { (day, entries) -> DailyUsage(day, entries.sumOf { it.generatedTokens }) }
            .sortedBy { it.day }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

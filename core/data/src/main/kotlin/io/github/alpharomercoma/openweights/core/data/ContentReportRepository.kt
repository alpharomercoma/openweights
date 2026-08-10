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

import io.github.alpharomercoma.openweights.core.data.db.ContentReportEntity
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Why a reply was reported. The list a user picks from, and what gets stored. */
enum class ReportReason(val wireName: String, val label: String) {
    OFFENSIVE("offensive", "Offensive or hateful"),
    SEXUAL("sexual", "Sexual content"),
    DANGEROUS("dangerous", "Dangerous or illegal advice"),
    HARASSMENT("harassment", "Harassment or threats"),
    WRONG("wrong", "Confidently wrong"),
    OTHER("other", "Something else"),
}

/**
 * Reports the user filed against something a model said.
 *
 * Kept on the device, like everything else here. This app has no backend to send them to
 * and has promised not to acquire one, so a report does two things: it records that a model
 * misbehaved, which is the only quality signal a local app can have, and it assembles the
 * text the user can choose to send onward themselves.
 */
@Singleton
class ContentReportRepository @Inject constructor(
    private val database: OpenWeightsDatabase,
    private val clock: Clock,
) {
    suspend fun report(
        modelName: String,
        reason: ReportReason,
        replyText: String,
        note: String = "",
    ): Long = database.reports().insert(
        ContentReportEntity(
            modelName = modelName,
            reason = reason.wireName,
            replyText = replyText,
            note = note.trim(),
            reportedAt = clock.nowMillis(),
        ),
    )

    fun observeAll(): Flow<List<ContentReportEntity>> = database.reports().observeAll()

    /** How many times this model has been reported, for the warning on the model itself. */
    suspend fun countFor(modelName: String): Int = database.reports().countFor(modelName)

    suspend fun delete(id: Long) = database.reports().delete(id)
}

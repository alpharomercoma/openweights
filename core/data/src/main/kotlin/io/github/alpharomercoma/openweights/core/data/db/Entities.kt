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

package io.github.alpharomercoma.openweights.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A conversation, titled from its first message. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val modelName: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** The compaction summary covering folded turns, if the conversation has been compacted. */
    val compactionSummary: String? = null,
    val compactionThroughIndex: Int = -1,
)

/**
 * One turn, with the measurements taken while producing it.
 *
 * Stats live here as well as in the ledger because they belong to this specific reply;
 * the ledger exists so totals survive deleting it.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    /** Raw model output, reasoning tags included, so it can be re-parsed or exported. */
    val text: String,
    val createdAt: Long,
    val tokensPerSecond: Double? = null,
    val timeToFirstTokenMs: Long? = null,
    val generatedTokens: Int? = null,
    val reasoningMs: Long? = null,
    /**
     * Files sent with this message, as JSON.
     *
     * A column rather than a table: attachments are only ever read with their message and
     * never queried across conversations, so a join would buy nothing.
     */
    val attachments: String? = null,
)

/**
 * Lifetime usage, one row per day per model.
 *
 * Deliberately append-only and separate from the conversations: a user who deletes a chat
 * has not un-generated those tokens, and a dashboard that shrinks when you tidy up is
 * lying. Day-bucketed keeps it tiny — a year of heavy use is a few hundred rows — and
 * makes the per-day chart a straight read.
 */
@Entity(tableName = "usage_ledger", primaryKeys = ["day", "modelName"])
data class UsageEntity(
    /** Days since the epoch, in the device's local time zone. */
    val day: Long,
    val modelName: String,
    val promptTokens: Long,
    val generatedTokens: Long,
    val inferenceMs: Long,
    val replies: Int,
)

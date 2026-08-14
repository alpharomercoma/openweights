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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM conversations")
    fun observeCount(): Flow<Int>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id")
    fun observeFor(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id")
    suspend fun forConversation(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id >= :fromId")
    suspend fun deleteFrom(conversationId: Long, fromId: Long)
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage_ledger ORDER BY day DESC")
    fun observeAll(): Flow<List<UsageEntity>>

    @Query("SELECT * FROM usage_ledger WHERE day = :day AND modelName = :modelName")
    suspend fun forDay(day: Long, modelName: String): UsageEntity?

    /** Every day this model has been used, for the shape of turn it is asked for. */
    @Query("SELECT * FROM usage_ledger WHERE modelName = :modelName")
    suspend fun forModel(modelName: String): List<UsageEntity>

    @Upsert
    suspend fun upsert(usage: UsageEntity)

    /**
     * Folds one reply into the day's running totals.
     *
     * A transaction because this is read-modify-write on a shared row, and two replies
     * finishing close together would otherwise lose one of them.
     */
    @Transaction
    suspend fun record(
        day: Long,
        modelName: String,
        promptTokens: Int,
        generatedTokens: Int,
        inferenceMs: Long,
    ) {
        val existing = forDay(day, modelName)
        upsert(
            UsageEntity(
                day = day,
                modelName = modelName,
                promptTokens = (existing?.promptTokens ?: 0) + promptTokens,
                generatedTokens = (existing?.generatedTokens ?: 0) + generatedTokens,
                inferenceMs = (existing?.inferenceMs ?: 0) + inferenceMs,
                replies = (existing?.replies ?: 0) + 1,
            ),
        )
    }
}

/** Reads and writes the reports the user filed against model output. */
@Dao
interface ContentReportDao {
    @Insert
    suspend fun insert(report: ContentReportEntity): Long

    @Query("SELECT * FROM content_reports ORDER BY reportedAt DESC")
    fun observeAll(): Flow<List<ContentReportEntity>>

    @Query("SELECT COUNT(*) FROM content_reports WHERE modelName = :modelName")
    suspend fun countFor(modelName: String): Int

    @Query("DELETE FROM content_reports WHERE id = :id")
    suspend fun delete(id: Long)
}

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
import androidx.room.OnConflictStrategy
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

    /**
     * Conversations whose title or whose text anywhere matches [term].
     *
     * `messages.text` is raw model output with its reasoning and tool markup still in it,
     * which is deliberate here: a search that could not find a word because the model
     * happened to say it while thinking would be a search the user cannot trust. What is
     * shown is cleaned before it reaches the screen; what is matched is everything.
     *
     * The inner select is wrapped because SQLite cannot filter on a result alias, and the
     * snippet has to be computed once rather than twice: it is the same subquery either way
     * and this shape makes it obvious that the row is only kept when something matched.
     *
     * LIKE rather than FTS. FTS would need a second table, a trigger to keep it in step and
     * a migration to create it, and it earns that on corpora far larger than a phone's chat
     * history. This scans a few thousand short rows, which is faster than the keystroke that
     * asked for it.
     *
     * `ESCAPE` because LIKE reads `%` and `_` as wildcards, so a search for "100%" would
     * otherwise match every conversation and a search for "a_b" would match "aXb". The
     * caller escapes them; this is the half that makes the escape character mean anything.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT c.id AS id, c.title AS title, c.modelName AS modelName,
                   c.updatedAt AS updatedAt,
                   (SELECT m.text FROM messages m
                     WHERE m.conversationId = c.id
                       AND m.text LIKE '%' || :term || '%' ESCAPE '\'
                     ORDER BY m.id LIMIT 1) AS snippet
            FROM conversations c
        )
        WHERE title LIKE '%' || :term || '%' ESCAPE '\' OR snippet IS NOT NULL
        ORDER BY updatedAt DESC
        """,
    )
    suspend fun search(term: String): List<ConversationMatch>
}

@Dao
interface MessageDao {
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

/**
 * The summaries a conversation has had, newest last.
 *
 * No update and no delete: rows are appended and the conversation points at one of them.
 * Deleting a conversation takes its summaries with it through the foreign key.
 */
@Dao
interface CompactionDao {
    @Query("SELECT * FROM compactions WHERE conversationId = :conversationId ORDER BY version")
    suspend fun forConversation(conversationId: Long): List<CompactionEntity>

    @Query("SELECT * FROM compactions WHERE id = :id")
    suspend fun byId(id: Long): CompactionEntity?

    @Insert
    suspend fun insert(compaction: CompactionEntity): Long
}

/** Watches and their history. */
@Dao
interface WatchDao {
    @Query("SELECT * FROM watches ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watches WHERE state = :state")
    suspend fun inState(state: String): List<WatchEntity>

    @Query("SELECT * FROM watches WHERE id = :id")
    suspend fun byId(id: Long): WatchEntity?

    @Query("SELECT COUNT(*) FROM watches WHERE state = :state")
    suspend fun countInState(state: String): Int

    @Insert
    suspend fun insert(watch: WatchEntity): Long

    @Upsert
    suspend fun upsert(watch: WatchEntity)

    @Query("DELETE FROM watches WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM watch_runs WHERE watchId = :watchId ORDER BY at DESC LIMIT :limit")
    fun observeRuns(watchId: Long, limit: Int): Flow<List<WatchRunEntity>>

    @Insert
    suspend fun insertRun(run: WatchRunEntity)

    /**
     * Drops all but the newest [keep] runs of one watch.
     *
     * By id rather than by timestamp, because two ticks of a one minute watch can land in
     * the same millisecond and a timestamp cut would then keep both or neither.
     */
    @Query(
        """
        DELETE FROM watch_runs WHERE watchId = :watchId AND id NOT IN (
            SELECT id FROM watch_runs WHERE watchId = :watchId ORDER BY id DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimRuns(watchId: Long, keep: Int)
}

/**
 * Reading and writing what the phone made.
 *
 * Everything comes back newest first and unfiltered. Sorting and filtering are done above
 * this, in shared code both platforms run, so that an iOS gallery cannot quietly disagree
 * with an Android one about what "favourites, images, last week" means.
 */
@Dao
interface GalleryDao {
    @Query("SELECT * FROM gallery ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<GalleryEntity>>

    @Query("SELECT * FROM gallery WHERE id = :id")
    suspend fun byId(id: Long): GalleryEntity?

    @Query("SELECT * FROM gallery ORDER BY createdAt DESC, id DESC")
    suspend fun all(): List<GalleryEntity>

    /**
     * Records one output, replacing any row that already claims the same path.
     *
     * Replace rather than ignore, because the second write is the newer account of the same
     * file: a retry that produced it again knows its real duration and backend, and the
     * first row's numbers describe an attempt that no longer exists.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: GalleryEntity): Long

    @Query("UPDATE gallery SET isFavourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("DELETE FROM gallery WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM gallery")
    suspend fun totalBytes(): Long

    @Query("SELECT COUNT(*) FROM gallery")
    suspend fun count(): Int
}

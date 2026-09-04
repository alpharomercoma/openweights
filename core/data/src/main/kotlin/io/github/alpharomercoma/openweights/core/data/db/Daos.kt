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
    /**
     * The conversations the drawer lists: everything that has not been filed away.
     *
     * Filtered in SQL rather than in the drawer. An archived conversation is not in this
     * list at all, which is what archiving means, and reading every row into memory only to
     * drop half of them again would make the archive cost more the more it was used.
     */
    @Query("SELECT * FROM conversations WHERE archivedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * The two writes that used to read a whole row and put it back.
     *
     * Statements for the same reason the three above are, and it is not a symmetry
     * argument: read-modify-write is only safe against writers that also read the whole
     * row, and the filing edits deliberately do not. A `setModel` that read an unpinned row
     * and upserted it after a pin landed put the row back exactly as it had been — pin
     * gone, and the new name with it. Nothing here reads what it does not intend to write.
     */
    @Query("UPDATE conversations SET updatedAt = :at, archivedAt = NULL WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("UPDATE conversations SET modelName = :modelName, updatedAt = :at WHERE id = :id")
    suspend fun setModel(id: Long, modelName: String?, at: Long)

    /**
     * Deliberately does not touch [ConversationEntity.updatedAt]: typing into a composer
     * is not activity worth reordering the drawer over, and a chat that leapt to the top
     * because its field held two characters would be the drawer crying wolf.
     */
    @Query("UPDATE conversations SET draft = :draft WHERE id = :id")
    suspend fun setDraft(id: Long, draft: String)

    /**
     * Points the conversation at a summary, or forgets the one it had.
     *
     * The last of the whole-row upserts, gone for the reason above: a fold is slow, it
     * reads the row before it starts, and a pin or a rename made while it ran was undone
     * by the snapshot it wrote at the end.
     */
    @Query(
        """
        UPDATE conversations
           SET compactionSummary = :summary,
               compactionThroughIndex = :throughIndex,
               compactionHeadId = :headId,
               updatedAt = :at
         WHERE id = :id
        """,
    )
    suspend fun setCompaction(
        id: Long,
        summary: String?,
        throughIndex: Int,
        headId: Long?,
        at: Long,
    )

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
                   c.pinnedAt AS pinnedAt, c.archivedAt AS archivedAt,
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

/**
 * The archive: conversations filed out of the drawer, and how many there are.
 *
 * Its own DAO and its own queries, not a filter applied to [ConversationDao.observeAll]
 * after the fact. The drawer needs the count and nothing else — a number, whatever the
 * archive holds — and the archive screen needs the rows, but only while it is open. Read
 * together they would put a lifetime of filed conversations in memory to draw one digit.
 *
 * Ordered by [ConversationEntity.updatedAt] rather than by when each was archived. The
 * question somebody brings to an archive is "when did I have that conversation", which is
 * the same chronology the drawer already sorts by; "when did I file it" is a question about
 * the filing rather than about the chat, and nobody asks it.
 */
@Dao
interface ArchiveDao {
    @Query("SELECT * FROM conversations WHERE archivedAt IS NOT NULL ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<ConversationEntity>>

    @Query("SELECT COUNT(*) FROM conversations WHERE archivedAt IS NOT NULL")
    fun observeArchivedCount(): Flow<Int>

    /**
     * The same search [ConversationDao.search] runs, over the archive alone.
     *
     * Scoped rather than title-only, and the difference matters: people remember a phrase
     * somebody said, not the title generated from their first message. Backing out of the
     * archive to use the drawer's search would be the app admitting its own screen cannot
     * answer the question it exists to answer.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT c.id AS id, c.title AS title, c.modelName AS modelName,
                   c.updatedAt AS updatedAt,
                   c.pinnedAt AS pinnedAt, c.archivedAt AS archivedAt,
                   (SELECT m.text FROM messages m
                     WHERE m.conversationId = c.id
                       AND m.text LIKE '%' || :term || '%' ESCAPE '\'
                     ORDER BY m.id LIMIT 1) AS snippet
            FROM conversations c
            WHERE c.archivedAt IS NOT NULL
        )
        WHERE title LIKE '%' || :term || '%' ESCAPE '\' OR snippet IS NOT NULL
        ORDER BY updatedAt DESC
        """,
    )
    suspend fun searchArchived(term: String): List<ConversationMatch>
}

/**
 * The three edits the drawer's overflow menu makes to a conversation without opening it.
 *
 * Its own DAO, matching [ConversationFiling], which is the only thing that calls it: these
 * write one column of one row and are about a conversation rather than about what is in
 * one. [ConversationDao] had grown past what static analysis will accept, and this is the
 * seam that was already there.
 */
@Dao
interface ConversationFilingDao {
    /**
     * Written as `UPDATE` rather than read-modify-write on purpose. The read-then-write
     * shape loses whichever of two concurrent edits finishes first — pin from the sheet
     * while a reply lands in the same conversation, and the reply's `touch` writes back a
     * row it read before the pin — and it is the same race the watch scheduler was fixed
     * for. One statement per column also means none of these touch [ConversationEntity.updatedAt],
     * which is what the day headings are made of: filing a chat is not talking in it, and a
     * chat that jumped to "Today" because it was renamed would be lying about itself.
     */
    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversations SET pinnedAt = :at WHERE id = :id")
    suspend fun setPinned(id: Long, at: Long?)

    @Query("UPDATE conversations SET archivedAt = :at WHERE id = :id")
    suspend fun setArchived(id: Long, at: Long?)
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
interface ToolStepDao {
    @Query(
        """
        SELECT tool_steps.* FROM tool_steps
        JOIN messages ON messages.id = tool_steps.messageId
        WHERE messages.conversationId = :conversationId
        ORDER BY tool_steps.messageId, tool_steps.orderIndex
        """,
    )
    suspend fun forConversation(conversationId: Long): List<ToolStepEntity>

    @Insert
    suspend fun insertAll(steps: List<ToolStepEntity>)
}

@Dao
interface EngineHistoryDao {
    @Query(
        "SELECT * FROM engine_history WHERE conversationId = :conversationId " +
            "ORDER BY orderIndex",
    )
    suspend fun forConversation(conversationId: Long): List<EngineHistoryEntity>

    @Query("DELETE FROM engine_history WHERE conversationId = :conversationId")
    suspend fun deleteFor(conversationId: Long)

    @Insert
    suspend fun insertAll(rows: List<EngineHistoryEntity>)

    /** The whole snapshot at once: whatever was there describes a history this replaces. */
    @Transaction
    suspend fun replaceFor(conversationId: Long, rows: List<EngineHistoryEntity>) {
        deleteFor(conversationId)
        if (rows.isNotEmpty()) insertAll(rows)
    }
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
     * Real, measured, decode-only throughput per model, heaviest-used first.
     *
     * [decodeTokens] over [decodeMs], not [UsageEntity.generatedTokens] over
     * [UsageEntity.inferenceMs]: that pair is prefill plus decode and every token including
     * the first, right for "how much of the day did this model cost" and wrong here twice
     * over — prefill scales with how long the prompt happened to be rather than with
     * anything about the model, and the two columns this reads only ever grow together, in
     * the same write, so a device upgrading mid-day cannot mix a token counted before this
     * pair existed against decode time measured after. A row with nothing decoded is zero
     * in both and excluded, not divided by.
     *
     * Weighted by tokens generated rather than averaged day by day, so one short reply's
     * day does not count the same as one that ran long enough to settle into steady state.
     */
    @Query(
        """
        SELECT modelName,
               SUM(decodeTokens) * 1000.0 / SUM(decodeMs) AS averageTokensPerSecond,
               SUM(decodeTokens) AS generatedTokens
        FROM usage_ledger
        WHERE decodeMs > 0
        GROUP BY modelName
        ORDER BY generatedTokens DESC
        """,
    )
    suspend fun decodeSpeedByModel(): List<ModelDecodeSpeed>

    /**
     * Real, measured, prompt-processing-only throughput per model, heaviest-used first.
     *
     * The prefill mirror of [decodeSpeedByModel]: same reasoning, same exclusion of rows
     * that predate the column, weighted by prompt tokens processed rather than tokens
     * generated, since a prefill measurement's confidence scales with how much prompt it
     * actually processed.
     */
    @Query(
        """
        SELECT modelName,
               SUM(prefillTokens) * 1000.0 / SUM(prefillMs) AS averageTokensPerSecond,
               SUM(prefillTokens) AS promptTokens
        FROM usage_ledger
        WHERE prefillMs > 0
        GROUP BY modelName
        ORDER BY promptTokens DESC
        """,
    )
    suspend fun prefillSpeedByModel(): List<ModelPrefillSpeed>

    /**
     * Folds one reply into the day's running totals.
     *
     * A transaction because this is read-modify-write on a shared row, and two replies
     * finishing close together would otherwise lose one of them.
     *
     * @param decodeMs zero unless this reply actually decoded more than one token: decode
     *   timing runs from the first token to the last, so a single-token reply has no decode
     *   interval to measure at all, only a prefill one.
     * @param decodeTokens [GenerationStats.decodeTokensPerSecond]'s own denominator, not
     *   [generatedTokens] verbatim: one fewer than tokens generated, since decode timing
     *   spans the gaps between tokens rather than the tokens themselves. Zero exactly when
     *   [decodeMs] is, so the two can never end up counting a different population.
     * @param prefillMs zero unless this reply actually had a prompt to process (a full cache
     *   hit has nothing to prefill).
     * @param prefillTokens [GenerationStats.prefillTokensPerSecond]'s own numerator: zero
     *   exactly when [prefillMs] is.
     */
    @Suppress("LongParameterList")
    @Transaction
    suspend fun record(
        day: Long,
        modelName: String,
        promptTokens: Int,
        generatedTokens: Int,
        inferenceMs: Long,
        decodeMs: Long,
        decodeTokens: Long,
        prefillMs: Long,
        prefillTokens: Long,
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
                decodeMs = (existing?.decodeMs ?: 0) + decodeMs,
                decodeTokens = (existing?.decodeTokens ?: 0) + decodeTokens,
                prefillMs = (existing?.prefillMs ?: 0) + prefillMs,
                prefillTokens = (existing?.prefillTokens ?: 0) + prefillTokens,
            ),
        )
    }
}

/** One model's real, measured, decode-only throughput on this device. See [UsageDao.decodeSpeedByModel]. */
data class ModelDecodeSpeed(
    val modelName: String,
    val averageTokensPerSecond: Double,
    val generatedTokens: Long,
)

/** One model's real, measured, prompt-processing-only throughput. See [UsageDao.prefillSpeedByModel]. */
data class ModelPrefillSpeed(
    val modelName: String,
    val averageTokensPerSecond: Double,
    val promptTokens: Long,
)

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

/** The watches themselves: what is scheduled, and what state each one is in. */
@Dao
interface WatchDao {
    @Query("SELECT * FROM watches ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watches WHERE state = :state")
    suspend fun inState(state: String): List<WatchEntity>

    @Query("SELECT * FROM watches WHERE id = :id")
    suspend fun byId(id: Long): WatchEntity?

    /**
     * Ends an active watch, and does nothing to one that has already ended.
     *
     * A statement rather than a read followed by a write, because the two racing was a real
     * hole: a startup sweep that had read a row as active could overwrite the FAILED a tick
     * wrote a moment later, and the screen would then say a broken watch had merely run out
     * of time. The `WHERE` clause is the whole guard, and SQLite applies it atomically.
     */
    @Query("UPDATE watches SET state = :state WHERE id = :id AND state = 'ACTIVE'")
    suspend fun endIfActive(id: Long, state: String): Int

    /** Moves the next deadline, for a watch that is still running. Same race, same guard. */
    @Query("UPDATE watches SET nextRunAt = :at WHERE id = :id AND state = 'ACTIVE'")
    suspend fun setNextRunAt(id: Long, at: Long): Int

    @Insert
    suspend fun insert(watch: WatchEntity): Long

    @Upsert
    suspend fun upsert(watch: WatchEntity)

    @Query("DELETE FROM watches WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * One watch's history of ticks, which is its own table and now its own door to it.
 *
 * Split from [WatchDao] rather than left beside it: the two answer different questions —
 * what is scheduled, and what has happened — and the schedule side grew two statements when
 * ending a watch and moving its deadline stopped being read-then-write.
 */
@Dao
interface WatchRunDao {
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

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
    /**
     * Which row of [CompactionEntity] is the current summary, or null before the first fold.
     *
     * One mutable pointer over an append-only log, which is the shape Claude Code and Codex
     * both settled on for a resumable session and the reason "always the latest" is a single
     * write rather than a search. The two columns above are the previous arrangement, kept
     * because a migration that dropped them would take every existing conversation's summary
     * with it; they are written alongside and read only when there is no head yet.
     */
    val compactionHeadId: Long? = null,
)

/**
 * One summary, as it was when it was written.
 *
 * Rows are never updated. Every fold appends, so the history of what the app believed about
 * a conversation is recoverable, and a summary can be read back against the model and the
 * prompt that produced it rather than being an anonymous blob of prose. The conversation
 * points at the current one; nothing points backwards, so an old row costs a few hundred
 * bytes and answers "what did it think two folds ago".
 */
@Entity(
    tableName = "compactions",
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
data class CompactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** One, two, three: what the user sees if they are ever shown the history. */
    val version: Int,
    val summary: String,
    /** The last transcript entry this summary covers. */
    val throughIndex: Int,
    /** Which model wrote it, because a summary is only as good as what produced it. */
    val modelName: String?,
    val createdAt: Long,
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
 * One conversation that matched a search, and the text that made it match.
 *
 * Not an entity: it is the shape of one query's answer and belongs to nothing else. The
 * snippet is null when the title matched and no message did, which the screen reads as
 * "this is here because of its name" and shows nothing under it.
 */
data class ConversationMatch(
    val id: Long,
    val title: String,
    val modelName: String?,
    val updatedAt: Long,
    val snippet: String?,
)

/**
 * Lifetime usage, one row per day per model.
 *
 * Append-only and separate from the conversations: a user who deletes a chat
 * has not un-generated those tokens, and a dashboard that shrinks when you tidy up is
 * lying. Day-bucketed keeps it tiny, a year of heavy use is a few hundred rows, and
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

/**
 * A reply the user flagged as offensive or wrong.
 *
 * Play requires an app that generates AI content to let people report it without leaving
 * the app. It is also the only signal this app can have about model behaviour, because
 * nothing is measured remotely: a model that earns reports is one worth warning the next
 * person about.
 *
 * The reply text is kept because a report with no example is not a report. It stays on the
 * device like everything else, and sending it anywhere is a separate, explicit choice.
 */
@Entity(tableName = "content_reports")
data class ContentReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Which model produced it, so repeat offenders are visible. */
    val modelName: String,
    /** One of the reasons offered in the sheet. */
    val reason: String,
    /** What the model actually said. */
    val replyText: String,
    /** Anything the user chose to add. Empty when they added nothing. */
    val note: String,
    val reportedAt: Long,
)

/**
 * A watch: something to check again on a schedule.
 *
 * Persisted rather than held in memory, which is the difference between a feature that keeps
 * running and one that stops the first time Android reclaims the process. The scheduler is
 * rebuilt from this table at startup.
 */
@Entity(tableName = "watches")
data class WatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val task: String,
    val everyMinutes: Int,
    /** [io.github.alpharomercoma.openweights.core.common.context.WatchState], by name. */
    val state: String,
    val createdAt: Long,
    val lastRunAt: Long? = null,
    val lastSummary: String? = null,
    val runs: Int = 0,
    val consecutiveFailures: Int = 0,
)

/**
 * One tick of a watch, kept so the user can see what it has been doing.
 *
 * Bounded per watch by the store rather than by a trigger: an unbounded log of a check that
 * runs every minute is a database that grows for as long as the phone is on.
 */
@Entity(
    tableName = "watch_runs",
    foreignKeys = [
        ForeignKey(
            entity = WatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["watchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("watchId")],
)
data class WatchRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val watchId: Long,
    val at: Long,
    /** [io.github.alpharomercoma.openweights.core.common.context.WatchOutcome], by name. */
    val outcome: String,
    val summary: String,
)

/**
 * One thing the phone made: a picture or a voice line, with the run that produced it.
 *
 * One table for both modalities, because they differ in two nullable columns and agree on
 * every other. Two tables would mean two of every query and two ways for a filter to be
 * subtly wrong about which of them it covered.
 *
 * `path` is unique. A generation writes its file and then records it, and a process that
 * dies between those two can be resumed by a retry that writes the same path again; without
 * the constraint the gallery would hold the same picture twice and deleting one would leave
 * a row pointing at nothing.
 */
@Entity(
    tableName = "gallery",
    indices = [Index(value = ["path"], unique = true), Index(value = ["createdAt"])],
)
data class GalleryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val mediaType: String,
    /** [io.github.alpharomercoma.openweights.core.generation.GenerationTask], by name. */
    val modality: String,
    val prompt: String,
    val negativePrompt: String = "",
    val bundleId: String,
    val bundleName: String,
    val seed: Long? = null,
    val createdAt: Long,
    val totalMillis: Long,
    val backend: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: Long? = null,
    val sizeBytes: Long = 0,
    val isFavourite: Boolean = false,
)

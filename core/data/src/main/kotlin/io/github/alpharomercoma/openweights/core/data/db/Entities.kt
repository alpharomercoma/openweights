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
    /**
     * Wall clock from send to finished, kept here for the same reason [tokensPerSecond] is:
     * it belongs to this specific reply, and a reopened conversation used to lose it, since
     * nothing on this row remembered it. Null on a stopped reply, same as the other numbers.
     */
    val totalMillis: Long? = null,
    /** This turn's prompt, cached and fresh tokens together. See [GenerationStats.totalPromptTokens]. */
    val promptTokens: Int? = null,
    /** How much of [promptTokens] the KV cache answered for free. See [GenerationStats.cachedTokens]. */
    val cachedTokens: Int? = null,
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
    /**
     * Decode time alone, out of [inferenceMs].
     *
     * [inferenceMs] is prefill plus decode, which is right for "how much of the day did
     * this model cost" and wrong for predicting a different model's decode speed: a long
     * prompt inflates it with prefill time that says nothing about the model's own
     * bandwidth-bound throughput. Zero on a row written before this column existed, which
     * [UsageDao.decodeSpeedByModel] excludes rather than divides by.
     */
    val decodeMs: Long = 0,
    /**
     * Tokens counted against [decodeMs] alone — not [generatedTokens], and deliberately a
     * separate column rather than reusing it.
     *
     * The day this device upgrades to the version that added [decodeMs], the row already on
     * disk has real [generatedTokens] from before with no [decodeMs] behind any of them. If
     * the next reply that same day accumulated into the *same* [generatedTokens] total,
     * every token generated before the upgrade would sit in the numerator of a rate whose
     * denominator only covers the tokens generated after it — overstating speed for exactly
     * one day per model. A column that only ever grows alongside [decodeMs], in the same
     * write, cannot mix the two populations no matter when the upgrade happened.
     *
     * Also [GenerationStats.decodeTokensPerSecond]'s own denominator, not [generatedTokens]
     * verbatim: decode timing runs from the first token to the last, which is one fewer
     * interval than tokens generated, and a ledger meant to approximate that same per-reply
     * rate across many replies has to use the same count or it systematically overstates
     * speed on short replies, where the difference is largest.
     */
    val decodeTokens: Long = 0,
    /**
     * Prompt-processing time alone, out of [inferenceMs]. See [decodeMs] — the same reasoning
     * applies in reverse: [decodeMs] scales with how long the reply happened to run rather
     * than with anything about the model's prefill bandwidth, so predicting prefill speed
     * needs its own column rather than a share of the combined total. Zero on a row written
     * before this column existed, which [UsageDao.prefillSpeedByModel] excludes.
     */
    val prefillMs: Long = 0,
    /**
     * Tokens counted against [prefillMs] alone. [GenerationStats.prefillTokensPerSecond]'s
     * own numerator ([promptTokens]), captured separately for the same reason [decodeTokens]
     * is: a column that only ever grows alongside [prefillMs], in the same write, cannot mix
     * pre-upgrade and post-upgrade populations.
     */
    val prefillTokens: Long = 0,
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

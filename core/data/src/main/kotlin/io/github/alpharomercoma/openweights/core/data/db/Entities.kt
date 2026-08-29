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
    /**
     * When this conversation was pinned, or null if it is not.
     *
     * A timestamp rather than a boolean, because a list of pinned chats has to be in some
     * order and "most recently pinned first" is the only one the user authored. Ordering
     * them by [updatedAt] would defeat the point: a chat is pinned precisely so it stops
     * moving when newer ones arrive.
     */
    val pinnedAt: Long? = null,
    /**
     * When this conversation was archived, or null if it is not.
     *
     * Archiving is the non-destructive half of the delete the drawer used to offer as its
     * only action: the rows and the attachments stay, the conversation simply leaves the
     * list. Cleared by [ChatRepository.touch], so saying anything in an archived chat files
     * it back — a conversation being used is not one that has been put away.
     */
    val archivedAt: Long? = null,
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
    /**
     * The other half of [tokensPerSecond]: how fast the prompt itself was processed, before
     * the first generated token. Kept apart because a phone measures them apart — prefill is
     * the batch pass over everything already written, decode is one token at a time — and a
     * single combined rate would hide whichever phase actually cost the reply its time. Null
     * on a full cache hit, where there is nothing left to prefill, same as
     * [GenerationStats.prefillTokensPerSecond].
     */
    val prefillTokensPerSecond: Double? = null,
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
    /** See [ConversationEntity.pinnedAt]. Carried so a result can say which section it is in. */
    val pinnedAt: Long? = null,
    /** See [ConversationEntity.archivedAt]. A search finds archived chats; the row says so. */
    val archivedAt: Long? = null,
)

/**
 * One tool call a reply made on its way to being written, kept past the turn that ran it.
 *
 * Only [MessageEntity.text] used to survive a reopen: the round trip that produced it — what
 * was called, with what arguments, and what came back — lived only in the in-memory turn and
 * was gone the moment the conversation was closed. Two things depended on it staying: the chip
 * the screen shows under a reply ("Searched the web for …"), and [ToolNotes], which a small
 * model needs re-grounded on every turn precisely because it cannot be trusted to carry a
 * fact forward on its own — see the tail-pinning in `TurnRunner.grounding`. A reopened
 * conversation had neither: no chips, and a model answering a follow-up with no memory of
 * what it had just looked up, which is the shape of a stale-entity conflation bug all over
 * again, just triggered by a chat switch instead of a fourth turn.
 *
 * Only [AgentStep.Ran] is worth a row. [AgentStep.Requested] never resolved to anything, and
 * [AgentStep.Skipped] taught the model nothing a future turn could use — both are already
 * filtered the same way before they reach [ToolNotes.withSteps].
 */
@Entity(
    tableName = "tool_steps",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class ToolStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    /** Preserves call order within a reply; SQLite gives no ordering guarantee by [id] alone. */
    val orderIndex: Int,
    val toolName: String,
    val argumentsJson: String,
    val result: String,
    val successful: Boolean,
    val millis: Long,
)

/**
 * The engine's own record of a conversation, one row per message it was actually sent.
 *
 * Not a copy of `messages`: the prompt the engine reads differs from the transcript in
 * exactly the ways that decide whether the KV cache survives a turn. The user message
 * carries the tool-notes digest and the grounding block it was decorated with; the tool
 * rounds sit between the question and the answer as the assistant calls and results the
 * template really rendered. Rebuilding a prompt from the transcript loses all of that, and
 * on a hybrid model — which cannot roll its recurrent state back at all — the first
 * mismatched token costs a full re-read of the whole conversation, measured at 1.7k tokens
 * on the turn after every tool turn.
 *
 * One snapshot per conversation, replaced whole when a reply completes. [replyMessageId]
 * names the reply the snapshot runs through: a snapshot whose reply is no longer the
 * conversation's last stored message describes a history that has since been edited,
 * regenerated or continued elsewhere, and is discarded rather than trusted.
 */
@Entity(
    tableName = "engine_history",
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
data class EngineHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** Preserves prompt order; SQLite gives no ordering guarantee by [id] alone. */
    val orderIndex: Int,
    val role: String,
    val text: String,
    val toolCallId: String,
    /** The reply this snapshot runs through. Stale when it is not the last message. */
    val replyMessageId: Long,
    /**
     * The model whose template rendered these messages. Not redundant with the
     * conversation's own model name: switching models renames the conversation, so by the
     * time a snapshot is read back the conversation may claim the new model while the
     * snapshot still holds the old one's rendered tool syntax. The snapshot is only
     * replayed into the template that produced it.
     */
    val modelName: String,
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
    /** When the next check is due, as whoever scheduled it last worked it out. */
    val nextRunAt: Long? = null,
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

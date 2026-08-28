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

import androidx.room.withTransaction
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.common.model.withoutToolMarkup
import io.github.alpharomercoma.openweights.core.data.db.CompactionEntity
import io.github.alpharomercoma.openweights.core.data.db.ConversationEntity
import io.github.alpharomercoma.openweights.core.data.db.ConversationMatch
import io.github.alpharomercoma.openweights.core.data.db.MessageEntity
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.data.db.ToolStepEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a reply's tool call looked like, in the shape [ChatRepository] can write down.
 *
 * A plain record rather than `core.tools.AgentStep`: this module does not depend on that one
 * and should not start to, since the tools a turn can call have no business knowing how a
 * conversation is stored. The caller — [io.github.alpharomercoma.openweights.ui.chat.ChatViewModel],
 * which already depends on both — does the translation.
 */
data class ToolStepRecord(
    val toolName: String,
    val argumentsJson: String,
    val result: String,
    val successful: Boolean,
    val millis: Long,
)

/** Reads and writes conversations, and records what each reply cost. */
@Singleton
class ChatRepository @Inject constructor(
    private val database: OpenWeightsDatabase,
    private val clock: Clock,
) {
    fun observeConversations(): Flow<List<ConversationEntity>> =
        database.conversations().observeAll()

    suspend fun conversation(id: Long): ConversationEntity? = database.conversations().byId(id)

    suspend fun messages(conversationId: Long): List<MessageEntity> =
        database.messages().forConversation(conversationId)

    /**
     * Every tool step any reply in this conversation recorded, keyed by the message it belongs
     * to.
     *
     * One query for the whole conversation rather than one per message: a reopen already reads
     * every message at once, and joining here costs nothing a per-message call would not have
     * paid many times over.
     */
    suspend fun toolSteps(conversationId: Long): Map<Long, List<ToolStepEntity>> =
        database.toolSteps().forConversation(conversationId).groupBy { it.messageId }

    /**
     * Starts a conversation.
     *
     * Titled from the first thing the user says, the way every chat app does it, because
     * "New conversation ×7" is a list you cannot navigate.
     */
    suspend fun startConversation(firstMessage: String, modelName: String?): Long {
        val now = clock.nowMillis()
        return database.conversations().insert(
            ConversationEntity(
                title = firstMessage.toTitle(),
                modelName = modelName,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Suppress("LongParameterList")
    suspend fun addMessage(
        conversationId: Long,
        role: String,
        text: String,
        tokensPerSecond: Double? = null,
        prefillTokensPerSecond: Double? = null,
        timeToFirstTokenMs: Long? = null,
        generatedTokens: Int? = null,
        reasoningMs: Long? = null,
        attachments: List<MessagePart.File> = emptyList(),
        totalMillis: Long? = null,
        promptTokens: Int? = null,
        cachedTokens: Int? = null,
        /**
         * What this reply's own tool calls found, in the order they ran.
         *
         * Written in the same transaction as the message, not after it: a process killed
         * between the two writes should not leave a reply on disk that claims no tool ever
         * ran when one did, which is a stronger and wronger claim than the row simply not
         * existing yet.
         */
        steps: List<ToolStepRecord> = emptyList(),
    ): Long = database.withTransaction {
        touch(conversationId)
        val messageId = database.messages().insert(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                text = text,
                createdAt = clock.nowMillis(),
                tokensPerSecond = tokensPerSecond,
                prefillTokensPerSecond = prefillTokensPerSecond,
                timeToFirstTokenMs = timeToFirstTokenMs,
                generatedTokens = generatedTokens,
                reasoningMs = reasoningMs,
                attachments = attachments.encodeAttachments(),
                totalMillis = totalMillis,
                promptTokens = promptTokens,
                cachedTokens = cachedTokens,
            ),
        )
        if (steps.isNotEmpty()) {
            database.toolSteps().insertAll(
                steps.mapIndexed { index, step ->
                    ToolStepEntity(
                        messageId = messageId,
                        orderIndex = index,
                        toolName = step.toolName,
                        argumentsJson = step.argumentsJson,
                        result = step.result,
                        successful = step.successful,
                        millis = step.millis,
                    )
                },
            )
        }
        messageId
    }

    /** Drops a message and everything after it: what regenerating a reply needs. */
    suspend fun deleteFrom(conversationId: Long, messageId: Long) {
        database.messages().deleteFrom(conversationId, messageId)
        touch(conversationId)
    }

    /** Replaces an edited turn, trims its suffix, and invalidates any stale summary atomically. */
    suspend fun replaceFrom(
        conversationId: Long,
        messageId: Long,
        text: String,
        attachments: List<MessagePart.File>,
        clearCompaction: Boolean,
    ) = database.withTransaction {
        val conversation = database.conversations().byId(conversationId)
            ?: return@withTransaction
        database.messages().deleteFrom(conversationId, messageId)
        database.messages().insert(
            MessageEntity(
                conversationId = conversationId,
                role = "user",
                text = text,
                createdAt = clock.nowMillis(),
                attachments = attachments.encodeAttachments(),
            ),
        )
        database.conversations().upsert(
            conversation.copy(
                compactionSummary = conversation.compactionSummary.takeUnless {
                    clearCompaction
                },
                compactionThroughIndex = conversation.compactionThroughIndex
                    .takeUnless { clearCompaction } ?: -1,
                compactionHeadId = conversation.compactionHeadId.takeUnless {
                    clearCompaction
                },
                updatedAt = clock.nowMillis(),
            ),
        )
    }

    suspend fun deleteConversation(id: Long) = database.conversations().delete(id)

    /**
     * Records which model a conversation is now running under.
     *
     * Chats can change model partway through, and the drawer shows the model beside each
     * one. Without this the list keeps naming whichever model happened to start it.
     */
    suspend fun setModel(id: Long, modelName: String?) {
        val conversation = database.conversations().byId(id) ?: return
        database.conversations().upsert(
            conversation.copy(modelName = modelName, updatedAt = clock.nowMillis()),
        )
    }

    /**
     * Appends a summary and moves the head to it, or does neither.
     *
     * In one transaction, because it is two writes that only mean anything together: the row
     * is the summary and the pointer is which summary counts. Written separately, a process
     * killed between them left a log with an entry nothing referenced, and the next fold
     * counted versions from a log that disagreed with the conversation about how many there
     * were. Room runs the block on one connection and rolls the whole thing back if anything
     * in it throws.
     */
    suspend fun saveCompaction(
        conversationId: Long,
        summary: String,
        throughIndex: Int,
        modelName: String? = null,
    ) = database.withTransaction {
        val conversation = database.conversations().byId(conversationId) ?: return@withTransaction
        val version = database.compactions().forConversation(conversationId).size + 1
        val head = database.compactions().insert(
            CompactionEntity(
                conversationId = conversationId,
                version = version,
                summary = summary,
                throughIndex = throughIndex,
                modelName = modelName,
                createdAt = clock.nowMillis(),
            ),
        )
        database.conversations().upsert(
            conversation.copy(
                // Written as well as appended. A conversation folded before the log existed
                // has its summary only in these two columns, so they stay the fallback and
                // keeping them current means one code path can read either kind.
                compactionSummary = summary,
                compactionThroughIndex = throughIndex,
                compactionHeadId = head,
                updatedAt = clock.nowMillis(),
            ),
        )
    }

    /**
     * Every summary a conversation has had, oldest first.
     *
     * Nothing reads this yet beyond its test. It is here because an append-only log whose
     * history cannot be got at is just a slower way of overwriting, and because the first
     * thing anyone will want when a summary turns out to be wrong is the one before it.
     */
    suspend fun compactionHistory(conversationId: Long): List<CompactionEntity> =
        database.compactions().forConversation(conversationId)

    /**
     * Records one reply in the lifetime ledger.
     *
     * Written separately from the message so the totals survive deleting the chat: the
     * tokens were generated whether or not the conversation is still around, and a
     * dashboard that shrinks when you tidy up would be lying.
     */
    @Suppress("LongParameterList")
    suspend fun recordUsage(
        modelName: String,
        promptTokens: Int,
        generatedTokens: Int,
        inferenceMs: Long,
        decodeMs: Long,
        decodeTokens: Long,
        prefillMs: Long = 0,
        prefillTokens: Long = 0,
    ) = database.usage().record(
        day = clock.today(),
        modelName = modelName,
        promptTokens = promptTokens,
        generatedTokens = generatedTokens,
        inferenceMs = inferenceMs,
        decodeMs = decodeMs,
        decodeTokens = decodeTokens,
        prefillMs = prefillMs,
        prefillTokens = prefillTokens,
    )

    /**
     * What this model has been asked for so far, as prompt tokens to generated tokens.
     *
     * The shape of a turn is the only thing that decides whether the GPU is worth it, and
     * the ledger has been recording both halves since it existed. Summed over every day
     * rather than the last one, because the answer should not change with the calendar.
     */
    suspend fun turnShape(modelName: String): Pair<Long, Long> {
        val rows = database.usage().forModel(modelName)
        return rows.fold(0L to 0L) { (prompt, generated), row ->
            prompt + row.promptTokens to generated + row.generatedTokens
        }
    }

    /**
     * Conversations matching [term], newest first, each with the text that matched.
     *
     * Blank returns nothing rather than everything: a search box with nothing typed in it is
     * not a request for the whole history, and the drawer already shows that.
     *
     * The snippet is trimmed to a readable length here rather than in the query, because a
     * message can be thousands of characters and only the query knows nothing about which
     * part of it matched. Around the match is what a person is looking for.
     */
    suspend fun searchConversations(term: String): List<ConversationMatch> {
        val needle = term.trim()
        if (needle.isEmpty()) return emptyList()
        // Escaped for the query, plain for the snippet: one is a LIKE pattern and the other
        // is the text the user typed, and conflating them is how "100%" came to match every
        // conversation while highlighting nothing in any of them.
        return database.conversations().search(needle.escapedForLike()).map { row ->
            row.copy(snippet = row.snippet?.let { snippetAround(it, needle) })
        }
    }

    private suspend fun touch(conversationId: Long) {
        val conversation = database.conversations().byId(conversationId) ?: return
        database.conversations().upsert(conversation.copy(updatedAt = clock.nowMillis()))
    }
}

/**
 * The term as a LIKE pattern that matches itself and nothing else.
 *
 * The backslash first, or escaping the wildcards would then escape their escapes.
 */
private fun String.escapedForLike(): String = this
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

/**
 * The part of a matching message worth showing, around the word that matched.
 *
 * Two passes, because the answer alone is not always where the match was. First the answer,
 * which is what a preview should be; then everything with its markup taken out, for a word
 * the model only said while thinking, which is still a word the user watched go past and
 * expects to be able to find.
 *
 * What never happens is showing the tags. The first version fell back to the raw row, so a
 * match inside a reasoning block put `<think>` on screen, and it ran `parseAssistantReply`
 * over user messages too, which mangled anyone who had typed the word `<think>` themselves.
 */
private fun snippetAround(raw: String, needle: String): String? {
    val answer = parseAssistantReply(raw).answer.withoutToolMarkup().trim()
    val everything = raw.withoutReasoningTags().withoutToolMarkup().trim()
    val source = when {
        answer.contains(needle, ignoreCase = true) -> answer
        everything.contains(needle, ignoreCase = true) -> everything
        else -> everything
    }
    // Nothing rather than the raw row. A message that is entirely a tool call leaves both
    // cleaned forms empty, and falling back to the raw text put the call's own arguments in
    // the drawer: a file path out of somebody's shared folder, on a list, for a word they
    // searched. The row still appears under its title, which is the honest amount to show.
    if (source.isEmpty()) return null
    val at = source.indexOf(needle, ignoreCase = true)
    if (at < 0) return source.take(SNIPPET_CHARS)

    // A little before the match rather than starting at it, so the word has the sentence it
    // came from around it and the eye lands on the reason this row is here.
    val from = (at - SNIPPET_LEAD).coerceAtLeast(0)
    val to = (from + SNIPPET_CHARS).coerceAtMost(source.length)
    return buildString {
        if (from > 0) append("…")
        append(source.substring(from, to).trim())
        if (to < source.length) append("…")
    }
}

private const val MAX_TITLE_LENGTH = 60

/** Two lines at the drawer's width, which is what the row has room for. */
private const val SNIPPET_CHARS = 120

/** Enough before the match to carry the start of its sentence. */
private const val SNIPPET_LEAD = 32

/**
 * The text with the reasoning markers gone but the reasoning still in it.
 *
 * Different from [parseAssistantReply], which separates the two so one can be hidden. Here
 * everything is wanted and only the angle brackets are not.
 */
private fun String.withoutReasoningTags(): String =
    replace("<think>", " ").replace("</think>", " ").trim()

private fun String.toTitle(): String {
    val cleaned = trim().replace(Regex("\\s+"), " ")
    return when {
        cleaned.isEmpty() -> "New chat"
        cleaned.length <= MAX_TITLE_LENGTH -> cleaned
        else -> cleaned.take(MAX_TITLE_LENGTH).substringBeforeLast(' ') + "…"
    }
}

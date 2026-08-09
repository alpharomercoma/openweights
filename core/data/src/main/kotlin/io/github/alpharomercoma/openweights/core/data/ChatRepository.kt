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

import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.data.db.ConversationEntity
import io.github.alpharomercoma.openweights.core.data.db.MessageEntity
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Reads and writes conversations, and records what each reply cost. */
@Singleton
class ChatRepository @Inject constructor(
    private val database: OpenWeightsDatabase,
    private val clock: Clock,
) {
    fun observeConversations(): Flow<List<ConversationEntity>> =
        database.conversations().observeAll()

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        database.messages().observeFor(conversationId)

    suspend fun conversation(id: Long): ConversationEntity? = database.conversations().byId(id)

    suspend fun messages(conversationId: Long): List<MessageEntity> =
        database.messages().forConversation(conversationId)

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

    suspend fun addMessage(
        conversationId: Long,
        role: String,
        text: String,
        tokensPerSecond: Double? = null,
        timeToFirstTokenMs: Long? = null,
        generatedTokens: Int? = null,
        reasoningMs: Long? = null,
        attachments: List<MessagePart.File> = emptyList(),
    ): Long {
        touch(conversationId)
        return database.messages().insert(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                text = text,
                createdAt = clock.nowMillis(),
                tokensPerSecond = tokensPerSecond,
                timeToFirstTokenMs = timeToFirstTokenMs,
                generatedTokens = generatedTokens,
                reasoningMs = reasoningMs,
                attachments = attachments.encodeAttachments(),
            ),
        )
    }

    suspend fun updateMessage(message: MessageEntity) {
        database.messages().upsert(message)
        touch(message.conversationId)
    }

    /** Drops a message and everything after it — what regenerating a reply needs. */
    suspend fun deleteFrom(conversationId: Long, messageId: Long) {
        database.messages().deleteFrom(conversationId, messageId)
        touch(conversationId)
    }

    suspend fun deleteConversation(id: Long) = database.conversations().delete(id)

    suspend fun saveCompaction(conversationId: Long, summary: String, throughIndex: Int) {
        val conversation = database.conversations().byId(conversationId) ?: return
        database.conversations().upsert(
            conversation.copy(
                compactionSummary = summary,
                compactionThroughIndex = throughIndex,
                updatedAt = clock.nowMillis(),
            ),
        )
    }

    /**
     * Records one reply in the lifetime ledger.
     *
     * Written separately from the message so the totals survive deleting the chat: the
     * tokens were generated whether or not the conversation is still around, and a
     * dashboard that shrinks when you tidy up would be lying.
     */
    suspend fun recordUsage(
        modelName: String,
        promptTokens: Int,
        generatedTokens: Int,
        inferenceMs: Long,
    ) = database.usage().record(
        day = clock.today(),
        modelName = modelName,
        promptTokens = promptTokens,
        generatedTokens = generatedTokens,
        inferenceMs = inferenceMs,
    )

    private suspend fun touch(conversationId: Long) {
        val conversation = database.conversations().byId(conversationId) ?: return
        database.conversations().upsert(conversation.copy(updatedAt = clock.nowMillis()))
    }
}

private const val MAX_TITLE_LENGTH = 60

private fun String.toTitle(): String {
    val cleaned = trim().replace(Regex("\\s+"), " ")
    return when {
        cleaned.isEmpty() -> "New chat"
        cleaned.length <= MAX_TITLE_LENGTH -> cleaned
        else -> cleaned.take(MAX_TITLE_LENGTH).substringBeforeLast(' ') + "…"
    }
}

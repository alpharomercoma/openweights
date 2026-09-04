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

package io.github.alpharomercoma.openweights.ui.chat

import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.data.ChatRepository
import io.github.alpharomercoma.openweights.core.data.ToolStepRecord
import io.github.alpharomercoma.openweights.core.data.db.ConversationEntity
import io.github.alpharomercoma.openweights.core.data.db.ConversationMatch
import io.github.alpharomercoma.openweights.core.data.db.EngineHistoryEntity
import io.github.alpharomercoma.openweights.core.engine.GenerationStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * What the chat writes down, in the order the user produced it.
 *
 * Its own object because the ordering is a property of the queue rather than of any one
 * caller, and it was previously a mutex the view model held and every write site had to
 * remember to take. The two writes a turn makes live here for the same reason: they are
 * the pair the ledger and the transcript are kept in step by.
 */
open class ChatWriter @Inject constructor(private val chats: ChatRepository) {
    /**
     * Serializes everything that touches the conversation tables.
     *
     * Writes are launched rather than awaited, so the screen never waits on the disk. That
     * leaves them racing each other unless they queue: a reply written at the end of one
     * turn can otherwise land after the next question, and a regeneration can read the
     * table before the reply it means to delete has been inserted. The mutex is fair, so
     * the rows end up in the order the user produced them.
     */
    private val mutex = Mutex()

    /**
     * The conversation list, which needs no queue: it is a flow the database keeps current.
     *
     * Here so the view model has one collaborator for storage rather than two, and cannot
     * reach the tables without going past the ordering this object exists to provide.
     */
    open fun conversations(): Flow<List<ConversationEntity>> = chats.observeConversations()

    /**
     * Conversations matching what was typed, which needs no queue for the same reason the
     * list does not: it is a read, and the worst a search running beside a write can be is
     * one message out of date.
     *
     * Deliberately not behind [inOrder]. A search runs on keystrokes and a turn writes a row
     * at the end of every reply, so queueing them together would make the drawer wait on the
     * disk exactly while the model is streaming, which is the one moment the app must not
     * stutter.
     *
     * That the read also does not block on the write is SQLite's doing rather than ours, and
     * only in write-ahead logging. Room picks the journal mode automatically and drops WAL on
     * a device the platform calls low-RAM, where a write does take the file and this read
     * would wait behind it. Bounded rather than fixed: what it waits on is one insert per
     * reply, not one per token, and the alternative costs those phones the memory WAL wants.
     */
    open suspend fun search(term: String): List<ConversationMatch> = chats.searchConversations(term)

    /** Runs [work] with the queue held, for the reads and writes with no method here. */
    open suspend fun <T> inOrder(work: suspend ChatRepository.() -> T): T = mutex.withLock {
        chats.work()
    }

    /**
     * Writes the reply a turn settled on.
     *
     * Once per turn, not once per pass. A turn that uses a tool completes two passes or
     * more, and this used to run at the end of each: the screen showed one answer with the
     * tool folded into it, storage held the interim reply that asked for the tool and then
     * the real one, and reopening the chat produced a conversation the user had never had
     * and then sent it back to the model as history.
     *
     * A stopped reply has no numbers, since they only arrive with a completion, and it is
     * written without them rather than with invented ones.
     *
     * @param totalMillis wall clock from send to finished. Not part of [stats]: the engine
     *   only ever measures its own prefill and decode time, and the gap between them — a
     *   tool call, the user's own thinking about whether to keep reading — is real elapsed
     *   time nothing else would otherwise account for. Null exactly when [stats] is.
     */
    suspend fun reply(
        conversationId: Long,
        text: String,
        stats: GenerationStats?,
        reasoningMs: Long?,
        totalMillis: Long? = null,
        steps: List<ToolStepRecord> = emptyList(),
        /**
         * The prompt the engine actually read for this turn, decoration and tool rounds
         * included, or null when there is none to keep. Stored beside the reply and stamped
         * with its row id, so a snapshot that outlives its reply — an edit, a regenerate,
         * a turn that kept no record — reads as stale instead of as the truth.
         */
        engineHistory: List<ChatMessage>? = null,
        /** The model whose template rendered [engineHistory]; see [EngineHistoryEntity.modelName]. */
        engineHistoryModel: String? = null,
    ) = inOrder {
        val messageId = addMessage(
            conversationId = conversationId,
            role = ChatRole.ASSISTANT.wireName,
            text = text,
            tokensPerSecond = stats?.decodeTokensPerSecond,
            prefillTokensPerSecond = stats?.prefillTokensPerSecond,
            timeToFirstTokenMs = stats?.timeToFirstTokenMs,
            generatedTokens = stats?.generatedTokens,
            reasoningMs = reasoningMs,
            totalMillis = totalMillis,
            promptTokens = stats?.totalPromptTokens,
            cachedTokens = stats?.cachedTokens,
            prefillMs = stats?.prefillMs,
            decodeMs = stats?.decodeMs,
            steps = steps,
        )
        engineHistory?.let { history ->
            replaceEngineHistory(
                conversationId,
                history.mapIndexed { index, message ->
                    EngineHistoryEntity(
                        conversationId = conversationId,
                        orderIndex = index,
                        role = message.role.wireName,
                        text = message.text,
                        toolCallId = message.toolCallId,
                        replyMessageId = messageId,
                        modelName = engineHistoryModel.orEmpty(),
                    )
                },
            )
        }
        messageId
    }

    /**
     * Folds one pass into the lifetime ledger.
     *
     * Per pass rather than per turn, which is the one part of this that was always right.
     * A turn that searched before answering really did decode twice, and the tab says how
     * much work the phone has done. Kept apart from [reply] for that reason: the row
     * carries the answer's own numbers, the ledger carries the totals, and deleting the
     * chat later does not un-count the work.
     */
    suspend fun work(modelName: String, stats: GenerationStats) = inOrder {
        // The same guard as GenerationStats.decodeTokensPerSecond, and for the same reason:
        // a reply with one token or less decoded has no decode interval to measure, only a
        // prefill one, and recording zero for both keeps this pass out of the calibration
        // average rather than counting it as an infinitely fast one.
        val decoded = stats.decodeMs > 0 && stats.generatedTokens > 1
        // Mirrors GenerationStats.prefillTokensPerSecond's own guard: a full cache hit has
        // nothing to prefill, and recording zero for both keeps it out of the average rather
        // than counting it as an infinitely fast one.
        val prefilled = stats.prefillMs > 0 && stats.promptTokens > 0
        recordUsage(
            modelName = modelName,
            promptTokens = stats.promptTokens,
            generatedTokens = stats.generatedTokens,
            inferenceMs = stats.prefillMs + stats.decodeMs,
            decodeMs = if (decoded) stats.decodeMs else 0,
            decodeTokens = if (decoded) (stats.generatedTokens - 1).toLong() else 0,
            prefillMs = if (prefilled) stats.prefillMs else 0,
            prefillTokens = if (prefilled) stats.promptTokens.toLong() else 0,
        )
    }
}

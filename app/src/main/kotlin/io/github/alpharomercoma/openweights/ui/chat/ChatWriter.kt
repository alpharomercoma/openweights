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

import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.data.ChatRepository
import io.github.alpharomercoma.openweights.core.engine.GenerationStats
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
     */
    suspend fun reply(
        conversationId: Long,
        text: String,
        stats: GenerationStats?,
        reasoningMs: Long?,
    ) = inOrder {
        addMessage(
            conversationId = conversationId,
            role = ChatRole.ASSISTANT.wireName,
            text = text,
            tokensPerSecond = stats?.decodeTokensPerSecond,
            timeToFirstTokenMs = stats?.timeToFirstTokenMs,
            generatedTokens = stats?.generatedTokens,
            reasoningMs = reasoningMs,
        )
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
        recordUsage(
            modelName = modelName,
            promptTokens = stats.promptTokens,
            generatedTokens = stats.generatedTokens,
            inferenceMs = stats.prefillMs + stats.decodeMs,
        )
    }
}

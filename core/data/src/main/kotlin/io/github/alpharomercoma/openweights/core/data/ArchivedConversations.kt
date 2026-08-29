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

import io.github.alpharomercoma.openweights.core.data.db.ArchiveDao
import io.github.alpharomercoma.openweights.core.data.db.ConversationEntity
import io.github.alpharomercoma.openweights.core.data.db.ConversationMatch
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What has been filed away, read apart from what has not.
 *
 * Three queries rather than one list the screens filter for themselves, because the two
 * readers want different things and neither wants the other's. The drawer wants a number,
 * so that the way into the archive can appear the moment there is an archive and say how
 * big it is; the archive screen wants the rows, and only while it is open. A single "give
 * me every conversation" read serving both would put a lifetime of filed chats in memory
 * to draw one digit, and would get slower every time the feature was used — the opposite
 * of what filing something away is supposed to do.
 *
 * See [ConversationFiling], which is what puts things here and takes them out again.
 */
@Singleton
open class ArchivedConversations @Inject constructor(database: OpenWeightsDatabase) {
    private val archive: ArchiveDao = database.archive()

    /** Every archived conversation, most recently talked in first. */
    open fun observe(): Flow<List<ConversationEntity>> = archive.observeArchived()

    /** How many there are, for the row that offers the way in. */
    open fun observeCount(): Flow<Int> = archive.observeArchivedCount()

    /**
     * Searches the archive by title and by anything said inside it.
     *
     * The same read the drawer's search runs, narrowed to archived rows, and escaped the
     * same way — see [escapedForLike] and the note on [ChatRepository.searchConversations]
     * about matching raw text and showing cleaned text.
     */
    open suspend fun search(term: String): List<ConversationMatch> {
        val needle = term.trim()
        if (needle.isEmpty()) return emptyList()
        return archive.searchArchived(needle.escapedForLike()).map { row ->
            row.copy(snippet = row.snippet?.let { snippetAround(it, needle) })
        }
    }
}

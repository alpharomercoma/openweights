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

import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the drawer does to a conversation without opening it: name it, pin it, file it away.
 *
 * Apart from [ChatRepository] on purpose, and not only because that class had grown past
 * what static analysis will accept. These three write one column of one row and are the
 * only writes here that are about a conversation rather than about what is *in* one, so
 * they need neither the write queue that orders messages against replies nor a
 * transaction. Keeping them here says that: [ChatRepository] is the transcript, this is
 * the filing cabinet it sits in.
 *
 * The one place the two meet is [ChatRepository.touch], which clears `archivedAt` when
 * something is said — a conversation being used is not one that has been put away.
 *
 * Open for the same reason [ChatWriter] is: what the screen does when one of these will not
 * go through is worth a test, and a full disk is not something a test can arrange.
 */
@Singleton
open class ConversationFiling @Inject constructor(
    private val database: OpenWeightsDatabase,
    private val clock: Clock,
) {
    /**
     * Gives a conversation the name the user chose instead of the one its first message
     * gave it.
     *
     * Blank is refused rather than stored: an empty title leaves a row in the drawer that
     * cannot be told from the one above it, and there is no undo. Otherwise it goes
     * through the same title-making the first message goes through, so a name typed by
     * hand and a name taken from a question are the same kind of string — one line,
     * whitespace collapsed, cut at the same length. A title pasted from a paragraph would
     * otherwise be carried around forever and make every row in the list a different
     * height.
     *
     * Nothing else writes this column after `startConversation`, so a chosen name is not
     * at risk of being overwritten later — switching model rewrites `modelName`, not this.
     *
     * @return false when there was nothing but whitespace to save.
     */
    open suspend fun rename(id: Long, title: String): Boolean {
        if (title.isBlank()) return false
        database.conversationFiling().rename(id, title.asConversationTitle())
        return true
    }

    /** Pins a conversation to the top of the drawer, or unpins it. */
    open suspend fun setPinned(id: Long, pinned: Boolean) =
        database.conversationFiling().setPinned(id, clock.nowMillis().takeIf { pinned })

    /** Files a conversation out of the drawer, or takes it back out. */
    open suspend fun setArchived(id: Long, archived: Boolean) =
        database.conversationFiling().setArchived(id, clock.nowMillis().takeIf { archived })
}

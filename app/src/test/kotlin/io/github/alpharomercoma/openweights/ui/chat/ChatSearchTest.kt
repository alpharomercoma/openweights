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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.data.ChatRepository
import io.github.alpharomercoma.openweights.core.data.Clock
import io.github.alpharomercoma.openweights.core.data.db.ConversationMatch
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The drawer's search against a delete that lands while it is still reading.
 *
 * The list on screen is a snapshot, and a deleted row is filtered out of it in place. That
 * was only half the race: the keystroke before the delete had already started a read, and
 * its answer, arriving after, still had the row in it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatSearchTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        OpenWeightsDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val chats = ChatRepository(
        ApplicationProvider.getApplicationContext(),
        database,
        Clock.System,
    )

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a chat deleted while a read is in flight does not come back with the answer`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writer = object : ChatWriter(chats) {
            override suspend fun search(term: String): List<ConversationMatch> {
                gate.await()
                return listOf(match(1L), match(2L))
            }
        }
        val search = ChatSearch(writer, this)

        search.search("ada")
        // Past the debounce and into the read, which is now parked on the gate.
        advanceUntilIdle()
        search.forget(2L)
        gate.complete(Unit)
        advanceUntilIdle()

        // The answer still lands, so the drawer is not left saying it found nothing
        // about a search it never finished; only the deleted row is missing from it.
        assertThat(search.state.value.hasAnswer).isTrue()
        assertThat(search.state.value.results.map { it.id }).containsExactly(1L)
    }

    private fun match(id: Long) = ConversationMatch(
        id = id,
        title = "Chat $id",
        modelName = null,
        updatedAt = id,
        snippet = null,
    )
}

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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The storage rules the rest of the app depends on.
 *
 * Run against a real Room database on the host rather than a fake, because the behaviour
 * being checked is largely Room's: cascade deletes, upserts, and what survives what.
 */
@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    private lateinit var database: OpenWeightsDatabase
    private lateinit var repository: ChatRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenWeightsDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ChatRepository(database, FixedClock { now })
    }

    @After
    fun tearDown() = database.close()

    /** A clock the test owns, so day bucketing does not depend on when the suite runs. */
    private class FixedClock(private val millis: () -> Long) : Clock {
        override fun nowMillis(): Long = millis()
        override fun today(): Long = 20_000L
    }

    @Test
    fun `deleting a conversation removes its messages too`() = runTest {
        val id = repository.startConversation("First question", "model-a")
        repository.addMessage(id, ChatRole.USER.wireName, "First question")
        repository.addMessage(id, ChatRole.ASSISTANT.wireName, "An answer")
        assertThat(repository.messages(id)).hasSize(2)

        repository.deleteConversation(id)

        assertThat(repository.conversation(id)).isNull()
        assertThat(repository.messages(id)).isEmpty()
    }

    @Test
    fun `deleting one conversation leaves the others alone`() = runTest {
        val kept = repository.startConversation("Keep me", "model-a")
        val removed = repository.startConversation("Remove me", "model-a")
        repository.addMessage(kept, ChatRole.USER.wireName, "Keep me")
        repository.addMessage(removed, ChatRole.USER.wireName, "Remove me")

        repository.deleteConversation(removed)

        assertThat(repository.conversation(kept)).isNotNull()
        assertThat(repository.messages(kept)).hasSize(1)
    }

    @Test
    fun `lifetime usage survives deleting the conversation that produced it`() = runTest {
        val id = repository.startConversation("Question", "model-a")
        repository.addMessage(id, ChatRole.USER.wireName, "Question")
        repository.recordUsage(
            modelName = "model-a",
            promptTokens = 10,
            generatedTokens = 40,
            inferenceMs = 2_000,
        )

        repository.deleteConversation(id)

        val ledger = database.usage().observeAll().first()
        assertThat(ledger).hasSize(1)
        assertThat(ledger.single().generatedTokens).isEqualTo(40)
    }

    @Test
    fun `attachments come back exactly as they went in`() = runTest {
        val id = repository.startConversation("Look at this", "model-a")
        val attached = listOf(
            MessagePart.File("/data/a.jpg", "image/jpeg", "photo.jpg"),
            MessagePart.File("/data/b.wav", "audio/wav"),
        )

        repository.addMessage(id, ChatRole.USER.wireName, "Look at this", attachments = attached)

        val stored = repository.messages(id).single()
        assertThat(stored.attachments.decodeAttachments()).isEqualTo(attached)
    }

    @Test
    fun `deleting from a message takes everything after it too`() = runTest {
        val id = repository.startConversation("Question", "model-a")
        repository.addMessage(id, ChatRole.USER.wireName, "Question")
        val firstReply = repository.addMessage(id, ChatRole.ASSISTANT.wireName, "Answer one")
        repository.addMessage(id, ChatRole.USER.wireName, "Follow up")

        repository.deleteFrom(id, firstReply)

        assertThat(repository.messages(id).map { it.text }).containsExactly("Question")
    }

    @Test
    fun `switching model renames the conversation's model`() = runTest {
        val id = repository.startConversation("Question", "model-a")

        repository.setModel(id, "model-b")

        assertThat(repository.conversation(id)?.modelName).isEqualTo("model-b")
    }

    @Test
    fun `a compaction summary is remembered so the chat does not resend everything`() = runTest {
        val id = repository.startConversation("Question", "model-a")

        repository.saveCompaction(id, "They discussed KV caches.", throughIndex = 3)

        val conversation = repository.conversation(id)
        assertThat(conversation?.compactionSummary).isEqualTo("They discussed KV caches.")
        assertThat(conversation?.compactionThroughIndex).isEqualTo(3)
    }

    @Test
    fun `usage for the same model on the same day accumulates into one row`() = runTest {
        repeat(3) {
            repository.recordUsage(
                modelName = "model-a",
                promptTokens = 5,
                generatedTokens = 10,
                inferenceMs = 100,
            )
        }

        val ledger = database.usage().observeAll().first()
        assertThat(ledger).hasSize(1)
        assertThat(ledger.single().generatedTokens).isEqualTo(30)
        assertThat(ledger.single().replies).isEqualTo(3)
    }
}

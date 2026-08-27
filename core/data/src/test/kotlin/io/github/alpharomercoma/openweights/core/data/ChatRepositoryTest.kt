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

    @Test
    fun `search finds a chat by something said inside it, not only by its title`() = runTest {
        val id = repository.startConversation("Ada Lovelace", modelName = "lfm")
        repository.addMessage(id, "assistant", "She wrote the first published algorithm.")
        repository.startConversation("Something else", modelName = "lfm")

        val found = repository.searchConversations("algorithm")

        assertThat(found.map { it.title }).containsExactly("Ada Lovelace")
        assertThat(found.single().snippet).contains("first published algorithm")
    }

    @Test
    fun `a title match with nothing said returns no snippet, not an empty one`() = runTest {
        repository.startConversation("KV caches", modelName = "lfm")

        val found = repository.searchConversations("caches")

        assertThat(found.single().title).isEqualTo("KV caches")
        assertThat(found.single().snippet).isNull()
    }

    @Test
    fun `searching matches what the model thought, and shows what it said`() = runTest {
        // The raw row is what a reply is stored as, reasoning tags and all. Matching has to
        // see all of it or a search fails for a word the user watched appear on screen; the
        // snippet has to show the answer, because a preview made of markup previews nothing.
        val id = repository.startConversation("Weather", modelName = "lfm")
        repository.addMessage(
            id,
            "assistant",
            "<think>The user wants barometric detail.</think>It will rain this afternoon.",
        )

        val onReasoning = repository.searchConversations("barometric")
        val onAnswer = repository.searchConversations("rain")

        assertThat(onReasoning).hasSize(1)
        assertThat(onReasoning.single().snippet).contains("barometric")
        assertThat(onAnswer.single().snippet).isEqualTo("It will rain this afternoon.")
    }

    @Test
    fun `a blank search is not a request for everything`() = runTest {
        repository.startConversation("Anything", modelName = "lfm")

        assertThat(repository.searchConversations("")).isEmpty()
        assertThat(repository.searchConversations("   ")).isEmpty()
    }

    @Test
    fun `the newest matching chat is first, since that is the one being looked for`() = runTest {
        val older = repository.startConversation("Tokens one", modelName = "lfm")
        repository.addMessage(older, "user", "how many tokens")
        now = 9_000L
        val newer = repository.startConversation("Tokens two", modelName = "lfm")
        repository.addMessage(newer, "user", "how many tokens")

        val found = repository.searchConversations("tokens")

        assertThat(found.map { it.id }).containsExactly(newer, older).inOrder()
    }

    @Test
    fun `a percent sign is a character to look for, not a wildcard`() = runTest {
        // LIKE reads % and _ as wildcards, so without escaping "100%" matched every
        // conversation ever held and "a_b" matched "axb". Both are ordinary things to have
        // said to a model.
        val battery = repository.startConversation("Battery", modelName = "lfm")
        repository.addMessage(battery, "assistant", "It is at 100% now.")
        repository.startConversation("Nothing to do with it", modelName = "lfm")

        assertThat(repository.searchConversations("100%").map { it.title })
            .containsExactly("Battery")
        assertThat(repository.searchConversations("%")).hasSize(1)
        assertThat(repository.searchConversations("_")).isEmpty()
    }

    @Test
    fun `a message that is only a tool call shows no snippet rather than its arguments`() =
        runTest {
            // The row still matches, because the query reads everything. What it must not do
            // is put the call's arguments on a list: that is a path out of somebody's shared
            // folder, shown because they searched for a word in it.
            val id = repository.startConversation("Notes", modelName = "lfm")
            repository.addMessage(
                id,
                "assistant",
                """<tool_call>{"name":"read_file","arguments":{"path":"/private/salary.txt"}}""",
            )

            val found = repository.searchConversations("salary")

            assertThat(found).hasSize(1)
            assertThat(found.single().snippet).isNull()
        }

    @Test
    fun `the first message that matches is the one previewed`() = runTest {
        val id = repository.startConversation("Long thread", modelName = "lfm")
        repository.addMessage(id, "user", "tell me about caching")
        repository.addMessage(id, "assistant", "Caching again, at more length.")

        val found = repository.searchConversations("caching")

        assertThat(found.single().snippet).isEqualTo("tell me about caching")
    }

    @Test
    fun `a backslash is a character to look for, like any other`() = runTest {
        // The one case that exercises escaping the escape: the backslash has to be doubled
        // before the wildcards are, or their escapes get escaped in turn.
        val id = repository.startConversation("Paths", modelName = "lfm")
        repository.addMessage(id, "user", "the path is C:\\Users\\alpha")

        assertThat(repository.searchConversations("C:\\Users")).hasSize(1)
    }

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
            decodeMs = 1_500,
            decodeTokens = 39,
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
    fun `every fold is kept and the conversation points at the newest`() = runTest {
        // Overwriting was the old behaviour, and it meant the app could not say what it had
        // believed about a conversation an hour ago, nor which model wrote the summary it is
        // now reasoning from. Rows are appended and one pointer moves, which is the shape a
        // resumable session wants: "the latest" is a single write rather than a search.
        val id = repository.startConversation("Question", "model-a")

        repository.saveCompaction(id, "First pass.", throughIndex = 3, modelName = "model-a")
        repository.saveCompaction(id, "Second pass.", throughIndex = 7, modelName = "model-b")

        val history = repository.compactionHistory(id)
        assertThat(history.map { it.version }).containsExactly(1, 2).inOrder()
        assertThat(history.map { it.summary })
            .containsExactly("First pass.", "Second pass.").inOrder()
        assertThat(history.map { it.modelName }).containsExactly("model-a", "model-b").inOrder()

        val conversation = repository.conversation(id)
        assertThat(conversation?.compactionHeadId).isEqualTo(history.last().id)
        // And the old columns still say the same thing, so a build that reads either is right.
        assertThat(conversation?.compactionSummary).isEqualTo("Second pass.")
        assertThat(conversation?.compactionThroughIndex).isEqualTo(7)
    }

    @Test
    fun `deleting a conversation takes its summaries with it`() = runTest {
        val id = repository.startConversation("Question", "model-a")
        repository.saveCompaction(id, "First pass.", throughIndex = 3)

        repository.deleteConversation(id)

        assertThat(repository.compactionHistory(id)).isEmpty()
    }

    @Test
    fun `usage for the same model on the same day accumulates into one row`() = runTest {
        repeat(3) {
            repository.recordUsage(
                modelName = "model-a",
                promptTokens = 5,
                generatedTokens = 10,
                inferenceMs = 100,
                decodeMs = 80,
                decodeTokens = 9,
            )
        }

        val ledger = database.usage().observeAll().first()
        assertThat(ledger).hasSize(1)
        assertThat(ledger.single().generatedTokens).isEqualTo(30)
        assertThat(ledger.single().replies).isEqualTo(3)
    }
}

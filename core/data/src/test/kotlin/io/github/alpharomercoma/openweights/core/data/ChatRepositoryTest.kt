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
    private lateinit var filing: ConversationFiling
    private lateinit var archived: ArchivedConversations
    private var now = 1_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenWeightsDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ChatRepository(
            ApplicationProvider.getApplicationContext(),
            database,
            FixedClock { now },
        )
        filing = ConversationFiling(database, FixedClock { now })
        archived = ArchivedConversations(database)
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

        val history = database.compactions().forConversation(id)
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

        assertThat(database.compactions().forConversation(id)).isEmpty()
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

    @Test
    fun `pinning a chat does not make it look like it was just used`() = runTest {
        // The day headings are made of updatedAt, so a pin that touched it would move the
        // conversation to Today and have the list claim something was said in it.
        val id = repository.startConversation("Taxes", modelName = "lfm")
        val before = repository.conversation(id)!!.updatedAt
        now += 5_000

        filing.setPinned(id, pinned = true)

        val after = repository.conversation(id)!!
        assertThat(after.updatedAt).isEqualTo(before)
        assertThat(after.pinnedAt).isEqualTo(now)
    }

    @Test
    fun `unpinning clears the timestamp rather than zeroing it`() = runTest {
        // Null and zero sort differently, and a chat pinned at the epoch would sit at the
        // bottom of the pinned section forever instead of leaving it.
        val id = repository.startConversation("Taxes", modelName = "lfm")
        filing.setPinned(id, pinned = true)

        filing.setPinned(id, pinned = false)

        assertThat(repository.conversation(id)!!.pinnedAt).isNull()
    }

    @Test
    fun `editing a turn in an archived chat takes it back out of the archive too`() = runTest {
        val id = repository.startConversation("Trip packing", modelName = "lfm")
        repository.addMessage(id, ChatRole.USER.wireName, "pack the tent")
        filing.setArchived(id, archived = true)
        val first = repository.messages(id).first().id

        repository.replaceFrom(id, first, "pack the tarp", emptyList(), clearCompaction = true)

        // An edit is saying something in the chat, the same as a new message is. This
        // write went through without the touch the other two make.
        assertThat(repository.conversation(id)!!.archivedAt).isNull()
    }

    @Test
    fun `saying something in an archived chat takes it back out of the archive`() = runTest {
        val id = repository.startConversation("Trip packing", modelName = "lfm")
        filing.setPinned(id, pinned = true)
        filing.setArchived(id, archived = true)

        repository.addMessage(id, ChatRole.USER.wireName, "one more thing")

        val after = repository.conversation(id)!!
        assertThat(after.archivedAt).isNull()
        // The pin is a choice about where it sits, not about whether it is filed, so
        // nothing said in the chat should undo it.
        assertThat(after.pinnedAt).isNotNull()
    }

    @Test
    fun `archiving keeps every message, which is the whole difference from deleting`() = runTest {
        val id = repository.startConversation("Trip packing", modelName = "lfm")
        repository.addMessage(id, ChatRole.ASSISTANT.wireName, "Take the small bag.")

        filing.setArchived(id, archived = true)

        assertThat(repository.messages(id).single().text).isEqualTo("Take the small bag.")
    }

    @Test
    fun `a search still finds a chat that has been archived, and says that it is`() = runTest {
        val id = repository.startConversation("Trip packing", modelName = "lfm")
        filing.setArchived(id, archived = true)

        val found = repository.searchConversations("packing").single()

        assertThat(found.id).isEqualTo(id)
        assertThat(found.archivedAt).isNotNull()
    }

    @Test
    fun `a chosen name replaces the one the first message gave it`() = runTest {
        val id = repository.startConversation("What is a KV cache?", modelName = "lfm")

        assertThat(filing.rename(id, "Cache notes")).isTrue()

        assertThat(repository.conversation(id)!!.title).isEqualTo("Cache notes")
    }

    @Test
    fun `a name of nothing but spaces is refused rather than stored`() = runTest {
        val id = repository.startConversation("What is a KV cache?", modelName = "lfm")

        assertThat(filing.rename(id, "   ")).isFalse()

        assertThat(repository.conversation(id)!!.title).isEqualTo("What is a KV cache?")
    }

    @Test
    fun `a pasted paragraph becomes a title, not a paragraph`() = runTest {
        // The same treatment a first message gets. A row in the drawer is one line at any
        // width, and a title with a newline in it made every row a different height.
        val id = repository.startConversation("Notes", modelName = "lfm")

        filing.rename(id, "  a very long name\nspread over lines ".repeat(20))

        val title = repository.conversation(id)!!.title
        assertThat(title).doesNotContain("\n")
        assertThat(title.length).isAtMost(80)
    }

    @Test
    fun `renaming does not move the chat to today either`() = runTest {
        val id = repository.startConversation("Notes", modelName = "lfm")
        val before = repository.conversation(id)!!.updatedAt
        now += 5_000

        filing.rename(id, "Cache notes")

        assertThat(repository.conversation(id)!!.updatedAt).isEqualTo(before)
    }

    @Test
    fun `switching model writes the model and nothing else`() = runTest {
        // `setModel` and `touch` used to read the whole row and put it back. That is only
        // safe against writers that also read the whole row, and the filing edits
        // deliberately do not: a pin or a rename landing between the read and the upsert
        // was silently undone by a snapshot taken before it. Both are single statements
        // now. Sequential here, because one virtual thread cannot produce the interleaving
        // — this holds the columns each write is allowed to touch, which is the property
        // that makes the interleaving harmless.
        val id = repository.startConversation("What is a KV cache?", modelName = "model-a")
        filing.setPinned(id, pinned = true)
        filing.rename(id, "Cache notes")

        repository.setModel(id, "model-b")

        val after = repository.conversation(id)!!
        assertThat(after.modelName).isEqualTo("model-b")
        assertThat(after.title).isEqualTo("Cache notes")
        assertThat(after.pinnedAt).isNotNull()
    }

    @Test
    fun `a message writes the time and unfiles, and nothing else`() = runTest {
        val id = repository.startConversation("What is a KV cache?", modelName = "model-a")
        filing.setPinned(id, pinned = true)
        filing.rename(id, "Cache notes")

        repository.addMessage(id, ChatRole.USER.wireName, "one more thing")

        val after = repository.conversation(id)!!
        assertThat(after.title).isEqualTo("Cache notes")
        assertThat(after.pinnedAt).isNotNull()
        assertThat(after.modelName).isEqualTo("model-a")
    }

    @Test
    fun `the drawer's list does not carry the archive around with it`() = runTest {
        // Excluded in SQL rather than filtered on screen. Reading every conversation into
        // memory only to drop half of them again would make the archive cost more the more
        // it was used, which is the opposite of what filing something away is for.
        val kept = repository.startConversation("Still here", modelName = "lfm")
        val filed = repository.startConversation("Trip packing", modelName = "lfm")
        filing.setArchived(filed, archived = true)

        val listed = repository.observeConversations().first()

        assertThat(listed.map { it.id }).containsExactly(kept)
        assertThat(archived.observe().first().map { it.id }).containsExactly(filed)
    }

    @Test
    fun `the archive count is a count, and it follows the filing`() = runTest {
        val id = repository.startConversation("Trip packing", modelName = "lfm")
        assertThat(archived.observeCount().first()).isEqualTo(0)

        filing.setArchived(id, archived = true)
        assertThat(archived.observeCount().first()).isEqualTo(1)

        filing.setArchived(id, archived = false)
        assertThat(archived.observeCount().first()).isEqualTo(0)
    }

    @Test
    fun `searching the archive reads what was said, and only in the archive`() = runTest {
        // Full text, not the titles: people remember a phrase somebody said, not the title
        // generated from their own first message.
        val filed = repository.startConversation("Trip packing", modelName = "lfm")
        repository.addMessage(filed, "assistant", "Take the small bag.")
        filing.setArchived(filed, archived = true)
        val live = repository.startConversation("Other plans", modelName = "lfm")
        repository.addMessage(live, "assistant", "Take the small bag as well.")

        val found = archived.search("small bag")

        assertThat(found.map { it.id }).containsExactly(filed)
        assertThat(found.single().snippet).contains("small bag")
    }

    @Test
    fun `a wildcard typed into the archive search matches itself`() = runTest {
        // The same escaping the drawer's search does. Without it "100%" matches everything.
        val filed = repository.startConversation("Discount", modelName = "lfm")
        repository.addMessage(filed, "assistant", "It was 100% off.")
        filing.setArchived(filed, archived = true)
        val other = repository.startConversation("Nothing to do with it", modelName = "lfm")
        filing.setArchived(other, archived = true)

        assertThat(archived.search("100%").map { it.id }).containsExactly(filed)
    }
}

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

package io.github.alpharomercoma.openweights.core.tools

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One thing worth carrying from one conversation into the next. */
data class Remembered(val text: String, val savedAt: Long)

/**
 * What the app knows about the user across conversations.
 *
 * ### Why a short list of facts and not a search over old chats
 *
 * The obvious build is retrieval: embed every past conversation, and when a question arrives
 * find the similar ones. It is also what the industry tried and moved away from. ChatGPT's
 * memory does no similarity search and has no vector database in the path; it keeps a small
 * set of explicit facts and puts them in front of the model on every message, and OpenAI's
 * own reported recall went from 41.5% to 82.8% when they invested in synthesising that
 * profile rather than in searching harder.
 *
 * On a phone the argument is stronger still. Retrieval needs an embedding model, which is a
 * second download, a second thing in memory and a second thing to keep warm. A short list
 * needs none of that.
 *
 * It also suits the one thing this app is careful about. Facts sit at the head of the
 * prompt, just after the instructions, where they are the same tokens on every turn: the KV
 * cache keeps them and they are paid for once rather than per question. A retrieved passage
 * would differ on every turn, land in the middle of the prompt, and cost a full re-prefill
 * each time, which is measured elsewhere here at eleven to nineteen seconds.
 *
 * ### What bounds it
 *
 * [MAX_FACTS] and [MAX_CHARS]. Everything here is prefill on every turn of every future
 * conversation, so it is capped at something a person could read in one screen, and the
 * oldest goes when the cap is reached. A memory that grows without limit is a context window
 * that shrinks without explanation.
 */
@Singleton
class Memory @Inject constructor(@param:ApplicationContext context: Context) {
    private val store = context.getSharedPreferences("memory", Context.MODE_PRIVATE)
    private val known = MutableStateFlow(read())

    val facts: StateFlow<List<Remembered>> = known.asStateFlow()

    /**
     * Saves a fact, or says why it was not saved.
     *
     * Deduplicated case-insensitively, because a model asked to remember something across
     * several turns will offer the same sentence more than once, and a list with the user's
     * name in it four times is worse than one that refused three of them.
     */
    fun remember(text: String, now: Long = System.currentTimeMillis()): ToolExecution {
        val fact = text.trim().replace(WHITESPACE, " ")
        if (fact.isEmpty()) {
            return ToolExecution.rejected("Nothing to remember: the note was empty.")
        }
        if (fact.length > MAX_CHARS) {
            return ToolExecution.rejected(
                "Too long to remember. Keep it under $MAX_CHARS characters, one fact.",
            )
        }
        if (known.value.any { it.text.equals(fact, ignoreCase = true) }) {
            // The fact is in memory, which is what was asked for. Nothing failed here.
            return ToolExecution("Already remembered.")
        }

        // Oldest first, so the cap costs the least recent thing rather than refusing the
        // most recent one. A memory that stops accepting is a memory nobody trusts.
        val kept = (known.value + Remembered(fact, now)).takeLast(MAX_FACTS).withinBudget()
        known.value = kept
        write(kept)
        return ToolExecution("Remembered.")
    }

    /**
     * Drops from the oldest until the block fits [MAX_TOTAL_CHARS].
     *
     * A count on its own was not a budget. Twenty four facts at the per-fact ceiling is
     * 3,725 characters, which the LFM2.5 tokenizer turns into 750 tokens of prefill on
     * every turn of every future conversation, against the 250 this was documented as
     * costing. Two limits that multiply need a third that does not.
     *
     * The newest fact always survives: it is the one somebody just asked for. A
     * [protected] fact survives for the same reason from the other door — it is the one
     * somebody just edited, and [replace] growing a fact must not evict the very fact it
     * grew, however old it is.
     */
    private fun List<Remembered>.withinBudget(protected: Remembered? = null): List<Remembered> {
        var total = sumOf { it.text.length }
        if (total <= MAX_TOTAL_CHARS) return this
        val kept = toMutableList()
        var index = 0
        while (kept.size > 1 && total > MAX_TOTAL_CHARS && index < kept.size) {
            if (kept[index] === protected) {
                index += 1
                continue
            }
            total -= kept.removeAt(index).text.length
        }
        return kept
    }

    fun forget(text: String) {
        val kept = known.value.filterNot { it.text.equals(text, ignoreCase = true) }
        known.value = kept
        write(kept)
    }

    /**
     * Rewrites one saved fact in place, keeping its age.

     * In place matters: [remember]-then-[forget] would make every correction the newest
     * fact, and the budget evicts oldest-first, so a fact would buy its way to safety by
     * being edited. The replacement passes the same gates a new fact does, because it is
     * about to be one.
     *
     * Rejections here and in [forgetMatching] never quote what is saved. Saving and
     * reading are two switches on purpose — what the app may keep, and what conversations
     * get told — and an error message that lists the facts would be the write half leaking
     * the read half.
     */
    fun replace(old: String, new: String): ToolExecution {
        val fact = new.trim().replace(WHITESPACE, " ")
        if (fact.isEmpty()) {
            return ToolExecution.rejected(
                "The replacement was empty. To remove a memory, use forget_memory.",
            )
        }
        if (fact.length > MAX_CHARS) {
            return ToolExecution.rejected(
                "Too long to remember. Keep it under $MAX_CHARS characters, one fact.",
            )
        }
        val found = matching(old)
            ?: return ToolExecution.rejected(NO_MATCH)
        if (known.value.any { it !== found && it.text.equals(fact, ignoreCase = true) }) {
            // The replacement already stands as its own fact, so the corrected one is
            // redundant rather than rewritten.
            forget(found.text)
            return ToolExecution("That is already remembered; the old version is forgotten.")
        }
        // The budget holds through edits too: a replacement longer than what it replaced
        // pays the way a new fact pays, from the oldest — never from the edited fact.
        val replaced = Remembered(fact, found.savedAt)
        val kept = known.value
            .map { if (it === found) replaced else it }
            .withinBudget(protected = replaced)
        known.value = kept
        write(kept)
        return ToolExecution("Updated.")
    }

    /** Drops the one saved fact matching [text], or says why it could not. */
    fun forgetMatching(text: String): ToolExecution {
        val found = matching(text)
            ?: return ToolExecution.rejected(NO_MATCH)
        forget(found.text)
        return ToolExecution("Forgotten.")
    }

    /**
     * The one fact [query] names, or null.
     *
     * Exact first, then a unique substring, because a small model quotes loosely: asked to
     * forget "the fact about my name" it will pass a fragment, and demanding the byte-exact
     * sentence back turns every edit into a read-then-retry round trip. A fragment matching
     * two facts matches neither — deleting on an ambiguous name is how the wrong thing goes.
     */
    private fun matching(query: String): Remembered? {
        val wanted = query.trim().replace(WHITESPACE, " ")
        if (wanted.isEmpty()) return null
        known.value.firstOrNull { it.text.equals(wanted, ignoreCase = true) }?.let { return it }
        return known.value.filter { it.text.contains(wanted, ignoreCase = true) }.singleOrNull()
    }

    fun forgetAll() {
        known.value = emptyList()
        write(emptyList())
    }

    /**
     * The block that goes into the system message, or null when there is nothing to say.
     *
     * Numbered and prefixed with what it is, because a bare list of sentences at the top of
     * a prompt reads to a small model as instructions to follow rather than as background.
     */
    fun asPrompt(): String? {
        val remembered = known.value
        if (remembered.isEmpty()) return null
        return buildString {
            append("Things you have been told about this user in earlier conversations. ")
            append("Use them if they are relevant and ignore them otherwise.")
            remembered.forEachIndexed { index, fact ->
                append("\n${index + 1}. ${fact.text}")
            }
        }
    }

    private fun read(): List<Remembered> = store.getStringSet(KEY, emptySet()).orEmpty()
        .mapNotNull { row ->
            val at = row.substringBefore(SEPARATOR).toLongOrNull() ?: return@mapNotNull null
            Remembered(row.substringAfter(SEPARATOR), at)
        }
        .sortedBy { it.savedAt }

    private fun write(facts: List<Remembered>) = store.edit {
        putStringSet(KEY, facts.map { "${it.savedAt}$SEPARATOR${it.text}" }.toSet())
    }

    companion object {
        /**
         * How many facts are carried.
         *
         * ChatGPT keeps a comparable number for the same reason: past a point the list
         * stops being a profile and becomes a transcript.
         *
         * This used to be the only limit, alongside [MAX_CHARS], and the pair was described
         * as costing about a thousand characters and 250 tokens. They multiply, so the
         * enforced worst case was 24 x 160, and measured rather than estimated, that is
         * 3,725 characters and **750 tokens** through the LFM2.5 tokenizer, on every turn
         * of every future conversation. [MAX_TOTAL_CHARS] is what makes the claim true.
         */
        const val MAX_FACTS = 24

        /**
         * The real budget: what every fact together may cost.
         *
         * A thousand characters, which the same measurement puts at roughly 230 tokens for
         * ordinary sentences, so this is the number the documentation always meant. Reached
         * long before [MAX_FACTS] for verbose facts and never for terse ones, which is the
         * right way round.
         */
        const val MAX_TOTAL_CHARS = 1_000

        /** One fact, one sentence. Anything longer is a summary and belongs in a chat. */
        const val MAX_CHARS = 160

        private const val KEY = "facts"

        /**
         * Said without quoting the saved facts; see [replace] for why. The pointer to
         * read_memory is honest even when that switch is off — the model then reports it
         * cannot, which is the configuration the user chose.
         */
        private const val NO_MATCH = "No saved memory matches that. Call read_memory to " +
            "see what is saved, then try again with the exact fact."

        /** Separates the timestamp from the text. Trimmed out of every fact on the way in. */
        private const val SEPARATOR = " "

        private val WHITESPACE = Regex("\\s+")
    }
}

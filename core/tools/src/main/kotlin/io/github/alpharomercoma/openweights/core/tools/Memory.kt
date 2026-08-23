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
    fun remember(text: String, now: Long = System.currentTimeMillis()): String {
        val fact = text.trim().replace(WHITESPACE, " ")
        if (fact.isEmpty()) return "Nothing to remember: the note was empty."
        if (fact.length > MAX_CHARS) {
            return "Too long to remember. Keep it under $MAX_CHARS characters, one fact."
        }
        if (known.value.any { it.text.equals(fact, ignoreCase = true) }) {
            return "Already remembered."
        }

        // Oldest first, so the cap costs the least recent thing rather than refusing the
        // most recent one. A memory that stops accepting is a memory nobody trusts.
        val kept = (known.value + Remembered(fact, now)).takeLast(MAX_FACTS).withinBudget()
        known.value = kept
        write(kept)
        return "Remembered."
    }

    /**
     * Drops from the oldest until the block fits [MAX_TOTAL_CHARS].
     *
     * A count on its own was not a budget. Twenty four facts at the per-fact ceiling is
     * 3,725 characters, which the LFM2.5 tokenizer turns into 750 tokens of prefill on
     * every turn of every future conversation, against the 250 this was documented as
     * costing. Two limits that multiply need a third that does not.
     *
     * The newest fact always survives: it is the one somebody just asked for.
     */
    private fun List<Remembered>.withinBudget(): List<Remembered> {
        var total = sumOf { it.text.length }
        if (total <= MAX_TOTAL_CHARS) return this
        val kept = toMutableList()
        while (kept.size > 1 && total > MAX_TOTAL_CHARS) {
            total -= kept.removeAt(0).text.length
        }
        return kept
    }

    fun forget(text: String) {
        val kept = known.value.filterNot { it.text.equals(text, ignoreCase = true) }
        known.value = kept
        write(kept)
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

        /** Separates the timestamp from the text. Trimmed out of every fact on the way in. */
        private const val SEPARATOR = " "

        private val WHITESPACE = Regex("\\s+")
    }
}

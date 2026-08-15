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

package io.github.alpharomercoma.openweights.core.common.context

/**
 * What has been folded away to keep the conversation inside the context window.
 *
 * @param summary the model's own summary of the folded turns.
 * @param foldedThroughIndex index of the last conversation entry the summary covers.
 * @param foldedEntryCount how many entries it replaced, for the marker shown in the UI.
 */
data class Compaction(val summary: String, val foldedThroughIndex: Int, val foldedEntryCount: Int)

/**
 * Decides when to compact a conversation and which turns to fold.
 *
 * A phone-sized context window fills quickly, and running into the wall mid-answer is the
 * worst possible moment to find out. So compaction is pre-emptive: once the cache passes
 * [triggerFraction], the older turns are summarized before the next turn starts. The most
 * recent turns stay verbatim because that is where the actual thread of the conversation
 * lives, and a summary of "what we were just saying" is both lossy and useless.
 *
 * Nothing is deleted. The full transcript stays on disk and on screen; compaction only
 * changes what gets sent to the model.
 */
class CompactionPolicy(
    private val triggerFraction: Float = DEFAULT_TRIGGER_FRACTION,
    private val keepRecentEntries: Int = DEFAULT_KEEP_RECENT,
) {
    init {
        require(triggerFraction in MIN_TRIGGER..MAX_TRIGGER) {
            "triggerFraction must be within $MIN_TRIGGER..$MAX_TRIGGER"
        }
        require(keepRecentEntries >= 2) { "at least one full exchange must stay verbatim" }
    }

    /** True when the KV cache is full enough that the next turn risks hitting the wall. */
    fun shouldCompact(contextUsed: Int, contextSize: Int, entryCount: Int): Boolean {
        if (contextSize <= 0) return false
        // Folding needs something to fold beyond the turns that must stay verbatim.
        if (entryCount <= keepRecentEntries + MIN_FOLDABLE_ENTRIES) return false
        return contextUsed.toFloat() / contextSize >= triggerFraction
    }

    /**
     * The range of entries to fold, or null when there is nothing to fold.
     *
     * @param alreadyFoldedThrough index of the last entry a previous compaction covered,
     *   or -1 if this is the first one.
     * @param isAnswer whether the entry at an index is the model's rather than the user's.
     *   The fold has to end on an answer, so that what is kept verbatim begins with a
     *   question: every template this app renders needs a question first, and an answer left
     *   at the front of the prompt is dropped on the way out. Landing the boundary in the
     *   middle of an exchange therefore lost that answer twice over, from the summary and
     *   from the prompt, and the model forgot what it had just said.
     */
    fun foldRange(
        entryCount: Int,
        alreadyFoldedThrough: Int = -1,
        isAnswer: (Int) -> Boolean = { false },
    ): IntRange? {
        val start = alreadyFoldedThrough + 1
        var endExclusive = entryCount - keepRecentEntries
        if (endExclusive - start < MIN_FOLDABLE_ENTRIES) return null
        // Never past the last entry: folding the whole transcript would leave a prompt with
        // nothing in it, which is a worse answer to this than keeping one answer too many.
        while (endExclusive < entryCount - 1 && isAnswer(endExclusive)) {
            endExclusive++
        }
        return start until endExclusive
    }

    companion object {
        /**
         * Compact at three-quarters full. Late enough that short chats never pay for it,
         * early enough to leave room for the summarization call itself.
         */
        const val DEFAULT_TRIGGER_FRACTION = 0.75f

        /** Two exchanges: enough that follow-up questions still resolve their referents. */
        const val DEFAULT_KEEP_RECENT = 4

        /** Folding fewer than this buys back less than the summary costs. */
        const val MIN_FOLDABLE_ENTRIES = 2

        /** Below this the summary would fire constantly; above it there is no room left. */
        private const val MIN_TRIGGER = 0.1f
        private const val MAX_TRIGGER = 0.99f
    }
}

/**
 * The instruction used to produce a compaction summary.
 *
 * Written for a small local model: concrete, ordered, and explicit about what to keep.
 * A 2B model given "summarize this" returns something decorative; given a list of things
 * to preserve, it returns something the conversation can actually continue from.
 */
fun compactionPrompt(conversationText: String): String =
    """
    Summarize the conversation below so it can be continued without the original text.

    Keep, in this order:
    1. What the user is trying to achieve.
    2. Decisions made and constraints agreed on.
    3. Facts established that later answers depend on.
    4. Anything left unresolved.

    Write plain prose under 200 words. Do not add commentary about summarizing.

    Conversation:
    $conversationText
    """.trimIndent()

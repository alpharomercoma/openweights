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
    /**
     * The most conversation to carry unfolded, however wide the window is.
     *
     * A fraction on its own was right while every model opened at 4096 and is wrong now that
     * the window is worked out per model: three quarters of 128,000 is 96,000 tokens, and a
     * conversation is never folded before then.
     *
     * The reason that matters is not memory. Attention reads the whole cache for every token
     * produced, so decode slows as the conversation grows whatever the window was declared
     * as, and the window is only what decides when folding is allowed to help.
     *
     * Measured on an MT6991 with LFM2.5 2.6B, and the shape is affine in the context length:
     * seconds per token = a + b*n, with a the weight-bound floor and b the cost of one more
     * token of history. Fitted on a bracketed run, empty then full then empty again after
     * resting, so that heat is ruled out rather than assumed: a = 0.0600, b = 3.42e-6, which
     * puts decode at 90% of its empty speed by 1,900 tokens, 80% by 4,400 and 75% by 5,800.
     *
     * So the ceiling is the answer to "how much slower than new is acceptable", and 4096 is
     * about a fifth slower on that hardware. It is deliberately below what this device could
     * bear, because it is also the number a phone nobody has measured has to live with. The
     * app already records decode milliseconds, tokens and context fill on every reply, so a
     * and b can be fitted from ordinary use and this constant become only a first guess.
     */
    private val ceilingTokens: Int = DEFAULT_CEILING_TOKENS,
) {
    init {
        require(triggerFraction in MIN_TRIGGER..MAX_TRIGGER) {
            "triggerFraction must be within $MIN_TRIGGER..$MAX_TRIGGER"
        }
        require(keepRecentEntries >= 2) { "at least one full exchange must stay verbatim" }
    }

    /**
     * True when the KV cache is full enough that the next turn risks hitting the wall.
     *
     * Two triggers, and they are not the same kind of thing.
     *
     * **The fraction is survival.** Past it the next answer may not fit, and a turn that runs
     * out of context mid-sentence is the worst outcome available. It fires whatever folding
     * costs and whatever it frees, because the alternative is worse.
     *
     * **The ceiling is an optimisation**, and an optimisation has to pay. Folding for speed
     * buys `b * freed` seconds on every token generated until the next fold, and costs one
     * long pause now. Measured on an SM8650 with LFM2.5 2.6B: a fold takes 24 to 34 seconds
     * to summarise and another 10 to 14 to read the rewritten prompt back, call it 40; a turn
     * adds about 110 tokens of context and generates about 160. Repaying 40 seconds before
     * the next fold needs
     *
     *     b * freed * (freed / 110) * 160  >  40
     *
     * **`b` is the number to be careful about, and the first value used here was wrong.** A
     * twenty turn conversation gave 6.36e-6 seconds per token per token, but context and
     * elapsed wall clock rise together in a conversation and the cores were at 95 C by the
     * end of it, so most of that slope was the chip slowing down rather than the cache
     * growing. Refitted on the depth sweep instead, where every reading was started below
     * 56 C and only the depth changed: **b = 2.82e-6**, with `a = 0.0399`, so `a/b` is 14,170
     * tokens. That agrees with the MT6991's independently fitted 3.42e-6 far better than the
     * confounded figure did, which is the other reason to believe it.
     *
     * At b = 2.82e-6 the break-even is `freed > 3,126` tokens rather than 2,080. A fold that
     * frees less is slower than not folding, and the app was doing exactly this: on a 4,096
     * ceiling with a 1,240 token system block, a 530 token summary and four kept entries, it
     * freed about 1,800 and lost time every time. So the ceiling now asks what the fold would
     * actually free, and declines when the answer is "not enough to matter". The context then
     * keeps growing until either the fold does pay or the fraction takes over, which is the
     * right order.
     *
     * @param foldableTokens what folding would remove, less what the summary will add back.
     *   Unknown by default, which keeps the old behaviour for callers that cannot estimate
     *   it: an unknown saving is treated as a large one.
     */
    fun shouldCompact(
        contextUsed: Int,
        contextSize: Int,
        entryCount: Int,
        foldableTokens: Int = Int.MAX_VALUE,
        /**
         * The user's own threshold, when they have moved it.
         *
         * Passed per call rather than held on the policy because the policy is a singleton
         * and this is a setting: one is constructed for the process, the other changes while
         * it runs.
         */
        triggerFraction: Float = this.triggerFraction,
    ): Boolean {
        // Folding needs something to fold beyond the turns that must stay verbatim.
        val canFold = contextSize > 0 &&
            entryCount > keepRecentEntries + MIN_FOLDABLE_ENTRIES
        if (!canFold) return false

        val fractionReached = contextUsed.toFloat() / contextSize >=
            triggerFraction.coerceIn(MIN_TRIGGER, MAX_TRIGGER)
        val worthwhileCeilingFold = contextUsed >= ceilingTokens &&
            foldableTokens >= MIN_WORTHWHILE_SAVING
        return fractionReached || worthwhileCeilingFold
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

        /** See [CompactionPolicy.ceilingTokens]: about a fifth slower than an empty context. */
        const val DEFAULT_CEILING_TOKENS = 4_096

        /** Two exchanges: enough that follow-up questions still resolve their referents. */
        const val DEFAULT_KEEP_RECENT = 4

        /** Folding fewer than this buys back less than the summary costs. */
        const val MIN_FOLDABLE_ENTRIES = 2

        /**
         * The tokens a fold has to free before folding for speed is worth the pause.
         *
         * Solved rather than chosen: see [CompactionPolicy.shouldCompact] for the arithmetic,
         * the measurements it rests on, and why the first version of this number was 2,000
         * and too small. Rounded down from 3,126 to something that is not pretending to more
         * precision than a wall clock and a fitted slope can carry.
         */
        const val MIN_WORTHWHILE_SAVING = 3_000

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

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

/**
 * Whether a reply has stopped saying anything new and is repeating itself.
 *
 * A model in a repetition loop spends its entire output budget echoing one fragment, and on
 * a phone that is not merely a bad answer: it is a minute of decoding, a warm handset and a
 * flat battery arriving at nothing. The sampler's own repetition penalty is what should
 * prevent it and at this size does not always, so the loop needs a floor under it.
 *
 * The check is deliberately conservative, because the cost of the two mistakes is not
 * symmetric. Cutting a reply that was going somewhere destroys work the user asked for;
 * letting a degenerate one run costs seconds. So it only trips on a *long* verbatim repeat
 * that covers most of what has been written, which is the signature of the failure and not
 * of any ordinary prose. Deliberately checked shapes that must never trip: a markdown table
 * whose rows share a prefix, a numbered list, code with similar-looking lines, and a short
 * reply that happens to repeat a heading.
 *
 * The shape of the test follows Nous Research's hermes-agent, whose own guard was written
 * after an incident where a single turn produced sixty thousand characters delivered as
 * thirty-one messages. The thresholds here are theirs; what differs is what happens next,
 * because this app has nothing to flood and a battery to protect, so the turn simply stops.
 */
internal object Degeneration {
    /**
     * Below this a reply is too short to judge.
     *
     * Set high on purpose, and higher than the mechanism needs. Repetition is bounded
     * already by the token ceiling, so the whole of what this guard can save is the tail of
     * one capped generation; against that, cutting a reply somebody asked for is the worse
     * mistake by a distance. Waiting until twelve hundred characters have been written
     * means a request that is *legitimately* repetitive — a hundred blank checklist rows,
     * a grid, a block of placeholder CSV — is finished and delivered before this ever looks
     * at it, while a model that is genuinely stuck has only spent a fraction of its budget.
     */
    const val MIN_CHARS = 1_200

    /** How often the check runs while text streams in, in characters written. */
    const val CHECK_EVERY = 512

    /**
     * How much of the end of the reply is examined.
     *
     * The end, rather than the whole thing, because that is where a loop is: an answer that
     * said three useful paragraphs and then began echoing is still looping, and a measure
     * taken over the whole reply would be diluted by the good part until the bad part
     * outgrew it. Looking only at the tail also keeps the cost flat as the answer grows.
     */
    private const val TAIL = 800

    /**
     * How many times over the fragment must appear to count.
     *
     * Three, back to back. Twice is a sentence someone chose to repeat for effect, or a
     * heading that follows its own rule; three consecutive identical runs filling the whole
     * tail of the reply is not something prose does.
     */
    private const val MIN_RUNS = 4

    /**
     * True when the reply now ends in the same fragment written back to back.
     *
     * Back to back is the whole test, and it is what makes this safe to run on every answer.
     * An earlier version asked whether some long fragment covered half the reply, which is
     * the shape Hermes looks for, and it fired on perfectly good output: a list of thirty
     * items sharing a sixty-character template, or a paragraph pattern reused per section,
     * is half-identical by construction and is not repeating itself in the sense that
     * matters. Hermes can afford that measure because it only asks the question about a
     * reply that already hit its length cap; this asks mid-stream, about everything, so it
     * has to be the stricter question.
     *
     * A degenerate model does not sprinkle a fragment through an answer, it emits the same
     * bytes immediately again, and periodicity says exactly that and nothing else.
     */
    fun dominates(text: String): Boolean {
        if (text.length < MIN_CHARS) return false
        val tail = text.takeLast(TAIL)
        val longest = tail.length / MIN_RUNS
        for (period in 1..longest) {
            if (repeatsWithPeriod(tail, period)) return true
        }
        return false
    }

    /**
     * Whether [tail] is the same [period] characters over and over, with nothing else in it.
     *
     * Compared against the character [period] places back rather than by cutting the string
     * into pieces, which is the same test without allocating a fragment per candidate.
     */
    private fun repeatsWithPeriod(tail: String, period: Int): Boolean {
        for (index in period until tail.length) {
            if (tail[index] != tail[index - period]) return false
        }
        return true
    }
}

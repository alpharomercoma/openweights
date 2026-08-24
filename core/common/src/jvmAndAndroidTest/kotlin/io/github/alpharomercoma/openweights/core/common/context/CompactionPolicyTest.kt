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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompactionPolicyTest {
    private val policy = CompactionPolicy()

    @Test
    fun `does not compact while there is room`() {
        assertThat(policy.shouldCompact(contextUsed = 100, contextSize = 2048, entryCount = 20))
            .isFalse()
    }

    @Test
    fun `compacts before the context window is actually full`() {
        // Hitting the wall mid-answer is the failure this exists to prevent, so the
        // trigger has to fire with room left for the summarization call itself.
        assertThat(policy.shouldCompact(contextUsed = 1600, contextSize = 2048, entryCount = 20))
            .isTrue()
    }

    @Test
    fun `does not compact a short conversation however full the cache is`() {
        // A long single answer can fill the cache; folding two turns would not help and
        // would throw away the only context there is.
        assertThat(policy.shouldCompact(contextUsed = 2000, contextSize = 2048, entryCount = 4))
            .isFalse()
    }

    @Test
    fun `keeps the most recent exchanges verbatim`() {
        val range = policy.foldRange(entryCount = 20)

        assertThat(range).isNotNull()
        assertThat(range!!.first).isEqualTo(0)
        // Four entries kept verbatim means folding stops at index 15.
        assertThat(range.last).isEqualTo(15)
    }

    @Test
    fun `resumes folding after a previous compaction`() {
        val range = policy.foldRange(entryCount = 30, alreadyFoldedThrough = 15)

        assertThat(range!!.first).isEqualTo(16)
        assertThat(range.last).isEqualTo(25)
    }

    @Test
    fun `the fold ends on an answer, so what is kept begins with a question`() {
        // Seven entries is question, answer, four times over and a question: the boundary
        // lands at index 3, which is an answer. Everything sent to the model has to start
        // with a question, so an answer left at the front is dropped on the way out, and
        // this one had not been summarised either. The model forgot what it had just said.
        val answers = setOf(1, 3, 5)

        val range = policy.foldRange(entryCount = 7) { it in answers }

        assertThat(range!!.last).isEqualTo(3)
    }

    @Test
    fun `an even boundary is left where it is`() {
        val answers = setOf(1, 3, 5, 7)

        val range = policy.foldRange(entryCount = 8) { it in answers }

        // Index 4 is a question already, so there is nothing to move.
        assertThat(range!!.last).isEqualTo(3)
    }

    @Test
    fun `the last entry is never folded, whatever role it has`() {
        // A transcript of nothing but answers cannot happen through the app, and if it ever
        // did, folding all of it would leave a prompt with nothing in it.
        val range = policy.foldRange(entryCount = 7) { true }

        assertThat(range!!.last).isEqualTo(5)
    }

    @Test
    fun `declines to fold when nothing new has accumulated`() {
        // Right after a compaction there is nothing between the summary and the turns
        // being kept verbatim, so a second pass would summarize a summary.
        assertThat(policy.foldRange(entryCount = 20, alreadyFoldedThrough = 15)).isNull()
    }

    @Test
    fun `rejects a configuration that would fold the entire conversation`() {
        assertThrows { CompactionPolicy(keepRecentEntries = 0) }
        assertThrows { CompactionPolicy(triggerFraction = 1.5f) }
    }

    @Test
    fun `the summarization prompt names what must survive`() {
        val prompt = compactionPrompt("user: hi")

        assertThat(prompt).contains("user: hi")
        assertThat(prompt).contains("unresolved")
    }

    private fun assertThrows(block: () -> Unit) {
        val threw = runCatching(block).isFailure
        assertThat(threw).isTrue()
    }

    @Test
    fun `the user's own threshold decides, not the built in one`() {
        // The setting exists because the trade is a preference. Half full is early folding:
        // faster decode, shorter memory. The policy default would not have folded here.
        val policy = CompactionPolicy()

        assertThat(
            policy.shouldCompact(
                contextUsed = 2_600,
                contextSize = 4_096,
                entryCount = 12,
                triggerFraction = 0.5f,
            ),
        ).isTrue()

        assertThat(
            policy.shouldCompact(contextUsed = 2_600, contextSize = 4_096, entryCount = 12),
        ).isFalse()
    }

    @Test
    fun `a threshold outside what the policy allows is clamped rather than obeyed`() {
        // A slider cannot send anything silly, but a stored preference from a future build
        // can, and a fraction of zero would fold before every single turn.
        val policy = CompactionPolicy()

        assertThat(
            policy.shouldCompact(
                contextUsed = 1,
                contextSize = 4_096,
                entryCount = 12,
                triggerFraction = 0f,
            ),
        ).isFalse()
    }
}

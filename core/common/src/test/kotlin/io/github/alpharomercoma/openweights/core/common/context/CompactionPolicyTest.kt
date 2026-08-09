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
}

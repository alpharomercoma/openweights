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

package io.github.alpharomercoma.openweights.core.common.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnswerLengthTest {
    @Test
    fun `the default is the one that caps nothing`() {
        assertThat(AnswerLength.fromName(null)).isEqualTo(AnswerLength.BALANCED)
        assertThat(AnswerLength.fromName("")).isEqualTo(AnswerLength.BALANCED)
        // A setting written by a later version, read by an earlier one.
        assertThat(AnswerLength.fromName("EXHAUSTIVE")).isEqualTo(AnswerLength.BALANCED)
    }

    @Test
    fun `every setting round trips through its stored name`() {
        AnswerLength.entries.forEach { length ->
            assertThat(AnswerLength.fromName(length.name)).isEqualTo(length)
        }
    }

    @Test
    fun `no instruction states a number, because a count is a cap by another name`() {
        // The failure this setting exists to undo was "in a few sentences", which the 1.2B
        // read as a rule and used to refuse a request for five paragraphs. A wording that
        // names a quantity invites the same reading.
        val forbidden = Regex("""\b(one|two|three|four|five|\d+)\s+(sentence|paragraph|word)""")
        AnswerLength.entries.forEach { length ->
            assertThat(forbidden.containsMatchIn(length.instruction.lowercase())).isFalse()
        }
    }

    @Test
    fun `each setting says something different`() {
        val instructions = AnswerLength.entries.map { it.instruction }
        assertThat(instructions).containsNoDuplicates()
        // All three start from the same place, so what differs is the length and nothing
        // else: a setting that also changed the tone would be two settings.
        instructions.forEach { assertThat(it).startsWith("Answer from what you know.") }
    }
}

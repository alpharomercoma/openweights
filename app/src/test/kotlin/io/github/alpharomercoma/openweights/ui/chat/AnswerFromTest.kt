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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Turning what the person tapped and typed into one sentence for the model.
 *
 * The card offers chips and a text box at the same time on purpose, so the interesting case
 * is somebody using both: two boxes ticked and a qualifier typed under them. Dropping either
 * half would answer a question they did not answer.
 */
class AnswerFromTest {
    private val options = listOf("Notes", "Documents", "Downloads")

    @Test
    fun `chips alone read as a list`() {
        val answer = answerFrom(chosen = setOf("Notes", "Documents"), typed = "", options = options)

        assertThat(answer).isEqualTo("Notes, Documents")
    }

    @Test
    fun `chips keep the order they were offered in, not the order they were tapped`() {
        // A set has no order of its own, so without this the model gets whichever order the
        // hash landed in, and the same two taps read differently on different runs.
        val answer = answerFrom(chosen = setOf("Downloads", "Notes"), typed = "", options = options)

        assertThat(answer).isEqualTo("Notes, Downloads")
    }

    @Test
    fun `typing alone is the whole answer`() {
        val answer = answerFrom(
            chosen = emptySet(),
            typed = "  the shared one  ",
            options = options,
        )

        assertThat(answer).isEqualTo("the shared one")
    }

    @Test
    fun `both halves survive`() {
        val answer = answerFrom(
            chosen = setOf("Notes"),
            typed = "but not the archived ones",
            options = options,
        )

        assertThat(answer).isEqualTo("Notes. but not the archived ones")
    }

    @Test
    fun `nothing at all is empty rather than punctuation`() {
        assertThat(answerFrom(chosen = emptySet(), typed = "   ", options = options)).isEmpty()
    }
}

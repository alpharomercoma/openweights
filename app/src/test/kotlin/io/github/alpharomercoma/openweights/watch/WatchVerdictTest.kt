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

package io.github.alpharomercoma.openweights.watch

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchVerdictTest {
    @Test
    fun `a first check is always news`() {
        val read = WatchVerdict.read("The price is 42 dollars.", previous = null)

        assertThat(read.changed).isTrue()
        assertThat(read.summary).isEqualTo("The price is 42 dollars.")
    }

    @Test
    fun `an unchanged verdict is quiet and the word never reaches the user`() {
        val read = WatchVerdict.read(
            "The price is still 42 dollars.\nUNCHANGED",
            previous = "The price is 42 dollars.",
        )

        assertThat(read.changed).isFalse()
        assertThat(read.summary).isEqualTo("The price is still 42 dollars.")
    }

    @Test
    fun `a changed verdict notifies, bold markup and punctuation included`() {
        val read = WatchVerdict.read(
            "It dropped to 39 dollars.\n**CHANGED**.",
            previous = "The price is 42 dollars.",
        )

        assertThat(read.changed).isTrue()
        assertThat(read.summary).isEqualTo("It dropped to 39 dollars.")
    }

    @Test
    fun `the word at the end of a sentence is not the verdict`() {
        val read = WatchVerdict.read(
            "The price has not CHANGED.",
            previous = "The price is 42 dollars.",
        )

        // Read as the verdict, the sentence lost its last word and the watch reported a
        // change it had just said did not happen. The verdict is a line of its own.
        assertThat(read.summary).isEqualTo("The price has not CHANGED.")
        assertThat(read.changed).isTrue() // Different text and no verdict line: fails open.
    }

    @Test
    fun `a model that forgot the word fails open unless it repeated itself verbatim`() {
        val previous = "The price is 42 dollars."

        assertThat(WatchVerdict.read("It moved to 39 dollars.", previous).changed).isTrue()
        assertThat(WatchVerdict.read(previous, previous).changed).isFalse()
    }

    @Test
    fun `a reply that is only the verdict still has something to record`() {
        val read = WatchVerdict.read("UNCHANGED", previous = "All quiet.")

        assertThat(read.summary).isEqualTo("Nothing new.")
        assertThat(read.changed).isFalse()
    }
}

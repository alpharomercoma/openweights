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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The skip that counts characters without a decoder, and where it must stop doing so.
 *
 * Every case checks two things: how many characters it claims to have skipped, and that
 * the stream it hands back decodes to exactly the rest of the text. The second is the one
 * that matters, since a byte swallowed or repeated at the hand-over is a character the
 * model's next page would lose or see twice.
 */
class AsciiSkipTest {
    @Test
    fun `ascii is counted one to one and skipped whole`() {
        val skip = "hello world".byteInputStream().skipAscii(6)

        assertThat(skip.chars).isEqualTo(6)
        assertThat(skip.rest.reader().readText()).isEqualTo("world")
    }

    @Test
    fun `the first byte that is not ascii stops the skip and stays in the stream`() {
        val skip = "ab€cd".byteInputStream().skipAscii(4)

        assertThat(skip.chars).isEqualTo(2)
        assertThat(skip.rest.reader().readText()).isEqualTo("€cd")
    }

    @Test
    fun `a stream shorter than the count ends the skip early`() {
        val skip = "abc".byteInputStream().skipAscii(10)

        assertThat(skip.chars).isEqualTo(3)
        assertThat(skip.rest.read()).isEqualTo(-1)
    }

    @Test
    fun `a chunk boundary changes nothing`() {
        val text = "x".repeat(9_000) + "é" + "y".repeat(10)

        listOf(8_191, 8_192, 8_193, 9_000, 9_001, 20_000).forEach { count ->
            val skip = text.byteInputStream().skipAscii(count.toLong())
            val reach = minOf(count, 9_000)
            assertWithMessage("count %s", count).that(skip.chars).isEqualTo(reach)
            assertWithMessage("count %s", count)
                .that(skip.rest.reader().readText())
                .isEqualTo(text.substring(reach))
        }
    }
}

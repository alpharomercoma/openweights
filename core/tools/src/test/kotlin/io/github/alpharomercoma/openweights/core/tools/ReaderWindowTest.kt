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
import org.junit.Test
import java.io.StringReader

class ReaderWindowTest {
    @Test
    fun `short skips still reach the requested offset`() {
        val reader = object : StringReader("0123456789") {
            override fun skip(ns: Long): Long = super.skip(minOf(ns, 2))
        }

        assertThat(reader.skipAsMuchAs(7)).isEqualTo(7)
        assertThat(reader.read()).isEqualTo('7'.code)
    }

    @Test
    fun `a reader that refuses to skip still makes progress`() {
        val reader = object : StringReader("abcdef") {
            override fun skip(ns: Long): Long = 0
        }

        assertThat(reader.skipAsMuchAs(4)).isEqualTo(4)
        assertThat(reader.read()).isEqualTo('e'.code)
    }

    @Test
    fun `end of input reports the offset actually reached`() {
        val reader = StringReader("abc")

        assertThat(reader.skipAsMuchAs(20)).isEqualTo(3)
        assertThat(reader.read()).isEqualTo(-1)
    }
}

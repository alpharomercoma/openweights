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

package io.github.alpharomercoma.openweights.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class DocumentWindowTest {
    @Test
    fun `a document read stops after the truncation probe`() {
        var consumed = 0
        val reader = object : Reader() {
            override fun read(target: CharArray, offset: Int, length: Int): Int {
                if (consumed >= 1_000_000) return -1
                val count = minOf(length, 3, 1_000_000 - consumed)
                repeat(count) { target[offset + it] = 'x' }
                consumed += count
                return count
            }

            override fun close() = Unit
        }

        val text = reader.readDocumentWindow(10)

        assertThat(text).hasLength(11)
        assertThat(consumed).isEqualTo(11)
    }

    @Test
    fun `a short document is returned whole`() {
        assertThat(StringReader("hello").readDocumentWindow(10)).isEqualTo("hello")
    }
}

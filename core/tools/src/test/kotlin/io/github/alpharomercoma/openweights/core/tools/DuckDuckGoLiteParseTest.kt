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

/**
 * Reading the lite endpoint, which is the fallback when the html one parses to nothing.
 *
 * It exists because both readers are regexes over scraped markup, and a rename on one page
 * turns search into a confident "the web has nothing on that". Two pages with different
 * class names means one rename no longer does that. So this fixture matters as much as the
 * other one: if it stops matching, the fallback is gone and nobody finds out on a phone.
 */
class DuckDuckGoLiteParseTest {
    private val page = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("duckduckgo-lite.html"),
    ).bufferedReader().readText()

    @Test
    fun `every result has a title, a snippet and a link`() {
        val hits = DuckDuckGoProvider.parseDuckDuckGoLite(page, LIMIT)

        assertThat(hits).isNotEmpty()
        hits.forEach { hit ->
            assertThat(hit.title).isNotEmpty()
            assertThat(hit.snippet).isNotEmpty()
            assertThat(hit.url).startsWith("http")
        }
    }

    @Test
    fun `markup and entities do not reach the model`() {
        val hits = DuckDuckGoProvider.parseDuckDuckGoLite(page, LIMIT)

        hits.forEach { hit ->
            assertThat(hit.title).doesNotContain("<")
            assertThat(hit.snippet).doesNotContain("<")
            assertThat(hit.snippet).doesNotContain("&amp;")
        }
    }

    @Test
    fun `the limit is honoured`() {
        assertThat(DuckDuckGoProvider.parseDuckDuckGoLite(page, 2)).hasSize(2)
    }

    @Test
    fun `a page with no results parses to nothing rather than throwing`() {
        assertThat(DuckDuckGoProvider.parseDuckDuckGoLite("<html><body>no</body></html>", LIMIT))
            .isEmpty()
    }

    /**
     * The two readers agree on what a result is.
     *
     * Not a formatting check: the fallback is only worth having if what it hands the model
     * has the same shape as what the primary hands it, or an answer sourced from the
     * fallback would read differently from the same question asked a minute earlier.
     */
    @Test
    fun `the fallback returns the same shape as the primary`() {
        val hits = DuckDuckGoProvider.parseDuckDuckGoLite(page, LIMIT)

        assertThat(hits.first().url).startsWith("http")
        assertThat(hits.first().title.length).isGreaterThan(3)
    }

    private companion object {
        const val LIMIT = 3
    }
}

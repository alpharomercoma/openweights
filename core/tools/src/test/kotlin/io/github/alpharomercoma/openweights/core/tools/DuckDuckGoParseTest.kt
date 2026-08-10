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
 * Reading a page of DuckDuckGo results.
 *
 * Against a real captured response, because this is scraping and the only thing keeping it
 * honest is a fixture that fails here when the markup moves, rather than on a phone as an
 * answer with no sources.
 */
class DuckDuckGoParseTest {
    private val page = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("duckduckgo-search.html"),
    ).bufferedReader().readText()

    @Test
    fun `every result has a title, a snippet and a link`() {
        val hits = DuckDuckGoProvider.parseDuckDuckGo(page, LIMIT)

        assertThat(hits).isNotEmpty()
        hits.forEach { hit ->
            assertThat(hit.title).isNotEmpty()
            assertThat(hit.snippet).isNotEmpty()
            assertThat(hit.url).startsWith("http")
        }
    }

    @Test
    fun `markup and entities do not reach the model`() {
        val hits = DuckDuckGoProvider.parseDuckDuckGo(page, LIMIT)

        hits.forEach { hit ->
            // A snippet arrives with the query terms wrapped in <b>, and an unescaped
            // ampersand reads as a broken entity in an answer that quotes it.
            assertThat(hit.title).doesNotContain("<")
            assertThat(hit.snippet).doesNotContain("<")
            assertThat(hit.snippet).doesNotContain("&amp;")
        }
    }

    @Test
    fun `the limit is honoured`() {
        assertThat(DuckDuckGoProvider.parseDuckDuckGo(page, 2)).hasSize(2)
    }

    @Test
    fun `a redirector is unwrapped to where it actually points`() {
        // One line, as a real href is: a newline inside the attribute would be markup we
        // have never seen and testing against it would only test the test.
        val wrapped = "<a class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=" +
            "https%3A%2F%2Fexample.com%2Fa&amp;rut=x\">Example</a>" +
            "<a class=\"result__snippet\">Some text.</a>"

        val hit = DuckDuckGoProvider.parseDuckDuckGo(wrapped, LIMIT).single()

        // Citing the redirector would make every source in every answer read as DuckDuckGo
        // rather than as whoever actually wrote the page.
        assertThat(hit.url).isEqualTo("https://example.com/a")
    }

    @Test
    fun `a rate limited page yields nothing rather than throwing`() {
        // What the endpoint actually returns when it is refusing: HTTP 202 and a page with
        // no results in it at all. The provider turns this into "could not answer".
        assertThat(DuckDuckGoProvider.parseDuckDuckGo("<html><body></body></html>", LIMIT))
            .isEmpty()
        assertThat(DuckDuckGoProvider.parseDuckDuckGo("", LIMIT)).isEmpty()
    }

    private companion object {
        const val LIMIT = 10
    }
}

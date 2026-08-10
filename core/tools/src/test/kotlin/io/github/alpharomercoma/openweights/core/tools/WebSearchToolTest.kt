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
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import org.junit.Test

/**
 * Reading search results.
 *
 * Against a response captured from the live API, so a change in its shape fails here
 * rather than on someone's phone as an empty answer.
 */
class WebSearchToolTest {
    /** Enough to exercise ordering without the tests depending on the shipped cap. */
    private val limit = 10

    private val payload = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("wikipedia-search.json"),
    ).bufferedReader().readText()

    @Test
    fun `results carry a title, something to read, and where it came from`() {
        val results = WikipediaProvider.parseWikipedia(payload, limit).orEmpty()

        assertThat(results).isNotEmpty()
        results.forEach { result ->
            assertThat(result.title).isNotEmpty()
            assertThat(result.snippet).isNotEmpty()
            // The link is what lets a reader check the answer, which for a model that can
            // be confidently wrong is the difference between a source and an assertion.
            assertThat(result.url).startsWith("https://en.wikipedia.org/wiki/")
        }
    }

    @Test
    fun `a title with spaces becomes a link that resolves`() {
        val results = WikipediaProvider.parseWikipedia(payload, limit).orEmpty()
        val spaced = results.firstOrNull { it.title.contains(' ') } ?: return

        assertThat(spaced.url).doesNotContain(" ")
        assertThat(spaced.url).contains("_")
    }

    @Test
    fun `extracts are capped so three of them still fit a phone's context`() {
        WikipediaProvider.parseWikipedia(payload, limit).orEmpty().forEach {
            assertThat(it.snippet.length).isAtMost(WikipediaProvider.MAX_EXTRACT_CHARS)
        }
    }

    @Test
    fun `results come back in the relevance order the API gave, not map order`() {
        // The pages arrive as an object keyed by page id. Reading them in map order is
        // reading them in whatever order the JSON was written, which is not a ranking.
        val shuffled = """
            {"query":{"pages":{
              "999":{"title":"Gustave Eiffel","index":2,"extract":"An engineer."},
              "111":{"title":"Eiffel Tower","index":1,"extract":"A tower in Paris."},
              "555":{"title":"Names on the tower","index":3,"extract":"A list."}
            }}}
        """.trimIndent()

        val results = WikipediaProvider.parseWikipedia(shuffled, limit).orEmpty()

        assertThat(results.map { it.title })
            .containsExactly("Eiffel Tower", "Gustave Eiffel", "Names on the tower")
            .inOrder()
    }

    @Test
    fun `an article with no text is not offered as a result`() {
        val empty = """{"query":{"pages":{"1":{"title":"Nothing","extract":""}}}}"""

        assertThat(WikipediaProvider.parseWikipedia(empty, limit).orEmpty()).isEmpty()
    }

    @Test
    fun `nonsense from the network is empty rather than a crash`() {
        assertThat(WikipediaProvider.parseWikipedia("", limit).orEmpty()).isEmpty()
        assertThat(
            WikipediaProvider.parseWikipedia("<html>rate limited</html>", limit).orEmpty(),
        ).isEmpty()
        assertThat(
            WikipediaProvider.parseWikipedia("""{"error":"nope"}""", limit).orEmpty(),
        ).isEmpty()
    }

    @Test
    fun `the query argument is read whatever the model decided to call it`() {
        val call = { json: String -> ToolCall("1", "web_search", json) }

        assertThat(call("""{"query":"a"}""").argument("query", "q", "search")).isEqualTo("a")
        assertThat(call("""{"q":"b"}""").argument("query", "q", "search")).isEqualTo("b")
        assertThat(call("""{"search":"c"}""").argument("query", "q", "search")).isEqualTo("c")
        // Malformed JSON is common from small models and must not throw.
        assertThat(call("not json").argument("query")).isNull()
    }
}

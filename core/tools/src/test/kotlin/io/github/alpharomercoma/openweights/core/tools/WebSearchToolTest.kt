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
    private val payload = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("wikipedia-search.json"),
    ).bufferedReader().readText()

    @Test
    fun `results carry a title, something to read, and where it came from`() {
        val results = WebSearchTool.parseResults(payload)

        assertThat(results).isNotEmpty()
        results.forEach { result ->
            assertThat(result.title).isNotEmpty()
            assertThat(result.extract).isNotEmpty()
            // The link is what lets a reader check the answer, which for a model that can
            // be confidently wrong is the difference between a source and an assertion.
            assertThat(result.url).startsWith("https://en.wikipedia.org/wiki/")
        }
    }

    @Test
    fun `a title with spaces becomes a link that resolves`() {
        val results = WebSearchTool.parseResults(payload)
        val spaced = results.firstOrNull { it.title.contains(' ') } ?: return

        assertThat(spaced.url).doesNotContain(" ")
        assertThat(spaced.url).contains("_")
    }

    @Test
    fun `extracts are capped so three of them still fit a phone's context`() {
        WebSearchTool.parseResults(payload).forEach {
            assertThat(it.extract.length).isAtMost(WebSearchTool.MAX_EXTRACT_CHARS)
        }
    }

    @Test
    fun `an article with no text is not offered as a result`() {
        val empty = """{"query":{"pages":{"1":{"title":"Nothing","extract":""}}}}"""

        assertThat(WebSearchTool.parseResults(empty)).isEmpty()
    }

    @Test
    fun `nonsense from the network is empty rather than a crash`() {
        assertThat(WebSearchTool.parseResults("")).isEmpty()
        assertThat(WebSearchTool.parseResults("<html>rate limited</html>")).isEmpty()
        assertThat(WebSearchTool.parseResults("""{"error":"nope"}""")).isEmpty()
    }

    @Test
    fun `the query argument is read whatever the model decided to call it`() {
        val call = { json: String -> ToolCall("1", "search_wikipedia", json) }

        assertThat(call("""{"query":"a"}""").argument("query", "q", "search")).isEqualTo("a")
        assertThat(call("""{"q":"b"}""").argument("query", "q", "search")).isEqualTo("b")
        assertThat(call("""{"search":"c"}""").argument("query", "q", "search")).isEqualTo("c")
        // Malformed JSON is common from small models and must not throw.
        assertThat(call("not json").argument("query")).isNull()
    }
}

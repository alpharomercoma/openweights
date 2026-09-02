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
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Reading what the model asked for.
 *
 * The parsing of results moved out of here with the Wikipedia provider, which is gone: what
 * ships now is scraped, and [DuckDuckGoParseTest] holds it to a captured page.
 */
class WebSearchToolTest {
    @Test
    fun `the query argument is read whatever the model decided to call it`() {
        val call = { json: String -> ToolCall("1", "web_search", json) }

        assertThat(call("""{"query":"a"}""").argument("query", "q", "search")).isEqualTo("a")
        assertThat(call("""{"q":"b"}""").argument("query", "q", "search")).isEqualTo("b")
        assertThat(call("""{"search":"c"}""").argument("query", "q", "search")).isEqualTo("c")
        // Malformed JSON is common from small models and must not throw.
        assertThat(call("not json").argument("query")).isNull()
    }

    private fun provider(called: String, answer: List<SearchHit>?) = object : SearchProvider {
        override val id = called
        override val label = called
        override val isConfigured = true
        override suspend fun search(query: String, limit: Int): List<SearchHit>? = answer
    }

    @Test
    fun `a provider that found nothing has answered, and the next one is not asked`() = runTest {
        // Null means the provider could not answer; an empty list means it looked and there
        // was nothing. The chain used to treat both as a failure, so a query with no hits
        // fell through every provider and came back as "the device may be offline".
        val empty = provider("empty", emptyList())
        val never = provider("never", listOf(SearchHit("Hit", "text", "https://example.test")))

        val answered = WebSearchTool.firstAnswer(listOf(empty, never), "nothing", 3)

        assertThat(answered?.first).isSameInstanceAs(empty)
        assertThat(answered?.second).isEmpty()
    }

    @Test
    fun `a provider that could not answer is passed over`() = runTest {
        val blocked = provider("blocked", null)
        val next = provider("next", emptyList())

        assertThat(WebSearchTool.firstAnswer(listOf(blocked, next), "q", 3)?.first)
            .isSameInstanceAs(next)
        assertThat(WebSearchTool.firstAnswer(listOf(blocked), "q", 3)).isNull()
    }

    @Test
    fun `successful search keeps exact normalized source addresses as typed evidence`() {
        val execution = WebSearchTool.webSearchSuccess(
            query = "topic",
            provider = "Example",
            results = listOf(
                SearchHit("One", "first", "https://example.test"),
                SearchHit("Two", "second", "https://example.test/two?q=1"),
            ),
        )

        assertThat(execution.successful).isTrue()
        assertThat((execution.evidence as ToolEvidence.Search).urls).containsExactly(
            "https://example.test/",
            "https://example.test/two?q=1",
        )
    }
}

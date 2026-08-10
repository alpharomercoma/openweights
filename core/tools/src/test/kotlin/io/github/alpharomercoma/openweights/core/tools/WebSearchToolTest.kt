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
}

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
 * How the lite reader pairs a link with its snippet.
 *
 * [DuckDuckGoLiteParseTest] holds the reader to a captured page, and that page has no
 * advertisement on it. This is the shape the captured page does not show: a row carrying a
 * link and no snippet, in front of the organic results.
 */
class SearchProvidersTest {
    @Test
    fun `a row with a link and no snippet does not take the next row's snippet`() {
        // Links and snippets were collected as two lists and paired by index, so the
        // advertisement took the first result's snippet and every later snippet moved one
        // result down. The model read one page's summary under another page's title.
        val page = """
            <table>
              <tr><td>Ad</td><td>
                <a rel="nofollow" href="https://ads.example/buy" class='result-link'>Buy now</a>
              </td></tr>
              <tr><td>1.</td><td>
                <a rel="nofollow" href="https://one.example/" class='result-link'>One</a>
              </td></tr>
              <tr><td></td><td class='result-snippet'>About one.</td></tr>
              <tr><td>2.</td><td>
                <a rel="nofollow" href="https://two.example/" class='result-link'>Two</a>
              </td></tr>
              <tr><td></td><td class='result-snippet'>About two.</td></tr>
            </table>
        """.trimIndent()

        val hits = DuckDuckGoProvider.parseDuckDuckGoLite(page, 5)

        assertThat(hits.map { it.title to it.snippet }).containsExactly(
            "Buy now" to "",
            "One" to "About one.",
            "Two" to "About two.",
        ).inOrder()
    }
}

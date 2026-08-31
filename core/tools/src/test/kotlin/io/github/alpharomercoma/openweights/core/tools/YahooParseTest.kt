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
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * Reading a page of Yahoo results, against a real captured response.
 *
 * The interesting part is the redirect: every organic anchor points at
 * `r.search.yahoo.com/...RU=<encoded destination>/...`, and a parse that stopped
 * decoding that would hand the model a page of tracking links. The capture is the
 * "capital of mongolia" query, whose right answer is unambiguous enough to assert.
 */
class YahooParseTest {
    private val page = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("yahoo-search.html"),
    ).bufferedReader().readText()

    private val provider = YahooProvider(OkHttpClient())

    @Test
    fun `results carry real destinations, not the tracking redirect`() {
        val hits = provider.parse(page, limit = 8)

        assertThat(hits).isNotEmpty()
        hits.forEach { hit ->
            assertThat(hit.url).doesNotContain("r.search.yahoo.com")
            assertThat(hit.url).startsWith("http")
            assertThat(hit.title).isNotEmpty()
        }
    }

    @Test
    fun `the answer to the captured query is in the first results`() {
        val hits = provider.parse(page, limit = 5)

        assertThat(hits.map { it.url }.any { "wikipedia.org" in it }).isTrue()
        assertThat(hits.any { "Ulaanbaatar" in it.title || "Ulaanbaatar" in it.snippet })
            .isTrue()
    }

    @Test
    fun `site navigation is not a result`() {
        val hits = provider.parse(page, limit = 20)

        hits.forEach { hit ->
            assertThat(hit.url).doesNotContain("mail.yahoo.com")
            assertThat(hit.url).doesNotContain("shopping.yahoo.com")
        }
    }
}

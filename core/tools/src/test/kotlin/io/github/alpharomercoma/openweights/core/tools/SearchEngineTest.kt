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

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which engines are tried, and the two rules that keep the chain honest.
 */
@RunWith(RobolectricTestRunner::class)
class SearchEngineTest {
    private val settings = SearchSettings(ApplicationProvider.getApplicationContext())
    private val client = OkHttpClient()

    @Test
    fun `all four are on to begin with`() {
        assertThat(settings.enabledEngines()).containsExactlyElementsIn(SearchEngine.entries)
    }

    @Test
    fun `google is tried last because it refuses most often`() {
        // Order is by how often each answers a phone, not by index quality. Putting the one
        // most likely to refuse first would make most searches wait for a refusal.
        assertThat(settings.enabledEngines().last()).isEqualTo(SearchEngine.GOOGLE)
    }

    @Test
    fun `switching one off takes it out of the chain`() {
        settings.setEnabled(SearchEngine.BING, false)

        assertThat(settings.enabledEngines()).doesNotContain(SearchEngine.BING)
        assertThat(settings.providers(client).map { it.id }).doesNotContain("bing")
    }

    @Test
    fun `the last engine cannot be switched off`() {
        // A search tool with nothing behind it reports that the web is unreachable, and the
        // model reads that as a fact about the web rather than about the settings.
        SearchEngine.entries.forEach { settings.setEnabled(it, false) }

        assertThat(settings.enabledEngines()).hasSize(1)
    }

    @Test
    fun `no proxy leaves the client exactly as it was`() {
        assertThat(settings.client(client)).isSameInstanceAs(client)
    }

    @Test
    fun `a proxy address is applied to search traffic`() {
        settings.proxy = "http://127.0.0.1:8080"

        val proxied = settings.client(client)
        assertThat(proxied).isNotSameInstanceAs(client)
        assertThat(proxied.proxy?.type()).isEqualTo(java.net.Proxy.Type.HTTP)
    }

    @Test
    fun `socks is understood, because that is what people have`() {
        settings.proxy = "socks5h://127.0.0.1:9150"

        assertThat(settings.client(client).proxy?.type()).isEqualTo(java.net.Proxy.Type.SOCKS)
    }

    @Test
    fun `an address that does not parse searches directly rather than throwing`() {
        // A typed setting should degrade to working, not to a search tool that raises.
        settings.proxy = "not a proxy"

        assertThat(settings.client(client)).isSameInstanceAs(client)
    }

    @Test
    fun `thumbnails are read back out of a tool result`() {
        val result = """
            Found 2 pictures for "otters".

            1. An otter
               image: https://example.com/a.jpg
               from https://example.com/page
            2. Another otter
               image: https://example.com/b.jpg
        """.trimIndent()

        assertThat(SearchMediaTool.thumbnailsIn(result))
            .containsExactly("https://example.com/a.jpg", "https://example.com/b.jpg")
            .inOrder()
    }

    @Test
    fun `a result with no pictures yields no grid`() {
        assertThat(SearchMediaTool.thumbnailsIn("The search did not answer.")).isEmpty()
    }
}

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
    private val settings =
        SearchSettings(ApplicationProvider.getApplicationContext(), SecretSealer.Unavailable)
    private val client = OkHttpClient()

    @Test
    fun `every engine is on to begin with`() {
        assertThat(settings.enabledEngines()).containsExactlyElementsIn(SearchEngine.entries)
    }

    @Test
    fun `duckduckgo leads because it answers most reliably`() {
        // Order is by how often each answers a phone, measured on both of this project's
        // devices, not by index quality.
        assertThat(settings.enabledEngines().first()).isEqualTo(SearchEngine.DUCKDUCKGO)
    }

    @Test
    fun `switching one off takes it out of the chain`() {
        settings.setEnabled(SearchEngine.YAHOO, false)

        assertThat(settings.enabledEngines()).doesNotContain(SearchEngine.YAHOO)
        assertThat(settings.providers(client).map { it.id }).doesNotContain("yahoo")
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
    fun `a thumbnail on the local network is never drawn`() {
        // The one place in this app where a remote party picks a URL the device fetches with
        // no tap and no approval: a thumbnail is loaded the moment a reply renders. A
        // poisoned search result carrying a LAN address would have the app probe the user's
        // own network as a side effect of drawing a message.
        listOf(
            "http://192.168.1.1/x.png",
            "https://192.168.1.1/x.png",
            "https://127.0.0.1/x.png",
            "https://10.0.0.5/x.png",
            "https://[::1]/x.png",
            // The two the hand-written host parser waved through. It cut at the first colon,
            // so an IPv6 host became "[fe80" and read as a domain name; and userinfo made a
            // private IPv4 address look like one too.
            "https://[fe80::1]/x.png",
            "https://[fd00::1]:8443/x.png",
            "https://user@192.168.1.1/x.png",
            // The dotted-userinfo form, which looks like a hostname to anything that does
            // not know where userinfo ends.
            "https://cdn.example@192.168.1.1/x.png",
            "https://localhost/x.png",
            "http://example.com/x.png",
        ).forEach { assertThat(it.isDrawable()).isFalse() }
    }

    @Test
    fun `an ordinary https thumbnail is drawn`() {
        assertThat("https://external-content.duckduckgo.com/iu/?u=x".isDrawable()).isTrue()
    }

    @Test
    fun `a picture carries both what to draw and what to open`() {
        // Tapping used to open the thumbnail while the comment beside it said it opened the
        // source page. Both addresses travel now, so the comment and the code agree.
        val result = """
            Found 1 pictures for "otters".

            1. An otter
               image: https://cdn.example.com/a.jpg https://example.com/page
        """.trimIndent()

        val picture = SearchMediaTool.picturesIn(result).single()

        assertThat(picture.thumbnail).isEqualTo("https://cdn.example.com/a.jpg")
        assertThat(picture.source).isEqualTo("https://example.com/page")
    }

    @Test
    fun `a source address keeps everything after the thumbnail`() {
        // Split on the first run of whitespace only. Splitting on every space cut a source
        // address at its first one and handed the user a truncated link.
        val result = "1. x\n   image: https://cdn.example.com/a.jpg https://example.com/a b"

        assertThat(SearchMediaTool.picturesIn(result).single().source)
            .isEqualTo("https://example.com/a b")
    }

    @Test
    fun `a source that is not safe to open falls back to the thumbnail`() {
        // The source is handed to an Intent, so it chooses an app as well as a host. An
        // unvalidated one lets a poisoned result pick both.
        val result = "1. x\n   image: https://cdn.example.com/a.jpg http://192.168.1.1/admin"

        val picture = SearchMediaTool.picturesIn(result).single()

        assertThat(picture.source).isEqualTo("https://cdn.example.com/a.jpg")
    }

    @Test
    fun `a picture with no source falls back to itself rather than to nothing`() {
        val result = "1. x\n   image: https://cdn.example.com/a.jpg"

        val picture = SearchMediaTool.picturesIn(result).single()

        assertThat(picture.source).isEqualTo("https://cdn.example.com/a.jpg")
    }

    @Test
    fun `a result with no pictures yields no grid`() {
        assertThat(SearchMediaTool.picturesIn("The search did not answer.")).isEmpty()
    }
}

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

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Base64

/**
 * The three general engines besides DuckDuckGo, each scraped from its own results page.
 *
 * Ported from what `ddgs` actually sends rather than from what its documentation says, which
 * matters because every one of these is a page meant for a browser and the difference
 * between an answer and a challenge page is usually one header.
 *
 * ### Why scraping, and what that costs
 *
 * None of these has a keyless API. Brave, Bing and Google all sell one, and a key belongs to
 * a person rather than to an app that promises nothing leaves the device without being
 * asked. So the app reads the page a browser would get, and pays for that in fragility: a
 * renamed class breaks a provider silently.
 *
 * Two things make that survivable. Parsing is by CSS selector rather than by regular
 * expression, so a changed attribute usually degrades rather than explodes. And a provider
 * that finds nothing returns null rather than an empty list, so the chain moves on instead
 * of telling the model the web is empty. That distinction is the single most important line
 * in this file: an empty result set is a claim about the world, and a scraper is never
 * entitled to make it.
 */
internal abstract class HtmlSearchProvider(private val client: OkHttpClient) : SearchProvider {
    override val isConfigured: Boolean = true

    /** The page to fetch for this query. */
    protected abstract fun request(query: String): Request

    /** Every result block on the page. */
    protected abstract fun results(page: org.jsoup.nodes.Document): List<Element>

    /** One result, or null when the block turned out to be an advert or a heading. */
    protected abstract fun hit(block: Element): SearchHit?

    override suspend fun search(query: String, limit: Int): List<SearchHit>? {
        val page = runCatching {
            client.newCall(request(query)).execute().use { response ->
                if (!response.isSuccessful) null else response.peekBody(MAX_PAGE_BYTES).string()
            }
        }.getOrNull() ?: return null

        val hits = runCatching {
            results(Jsoup.parse(page)).mapNotNull { hit(it) }
        }.getOrDefault(emptyList())

        // Never an empty list. See the note on this class.
        return hits.take(limit).ifEmpty { null }
    }

    protected fun Element.textOf(selector: String): String =
        selectFirst(selector)?.text()?.trim().orEmpty()

    private companion object {
        /** Generous for a results page, and an end where a remote server decides the size. */
        const val MAX_PAGE_BYTES = 1L shl 20
    }
}

/**
 * Brave, which has an index of its own rather than reselling someone else's.
 *
 * That independence is the reason to offer it: Bing and Google agreeing tells you less than
 * either agreeing with Brave, and a user who distrusts one of the large two has somewhere
 * to go that is not just a front end for them.
 */
internal class BraveProvider(client: OkHttpClient) : HtmlSearchProvider(client) {
    override val id = "brave"
    override val label = "Brave"

    override fun request(query: String) = Request.Builder()
        .url("https://search.brave.com/search?q=${query.urlEncoded()}&source=web")
        .header("User-Agent", SEARCH_USER_AGENT_BROWSER)
        .header("Accept", "text/html")
        // Brave reads location from a cookie and will otherwise interpose a consent step.
        .header("Cookie", "useLocation=0; safesearch=off")
        .build()

    override fun results(page: org.jsoup.nodes.Document) = page.select("div[data-type=web]")

    override fun hit(block: Element): SearchHit? {
        val link = block.selectFirst("a[href^=http]") ?: return null
        val title = block.textOf("div.title").ifBlank { link.text().trim() }
        if (title.isBlank()) return null
        return SearchHit(
            title = title,
            snippet = block.textOf("div.snippet"),
            url = link.attr("href"),
        )
    }
}

/**
 * Bing, which also answers for Yahoo and several others, so it is one index rather than two.
 */
internal class BingProvider(client: OkHttpClient) : HtmlSearchProvider(client) {
    override val id = "bing"
    override val label = "Bing"

    override fun request(query: String) = Request.Builder()
        .url("https://www.bing.com/search?q=${query.urlEncoded()}&pq=${query.urlEncoded()}")
        .header("User-Agent", SEARCH_USER_AGENT_BROWSER)
        .header("Accept", "text/html")
        .build()

    override fun results(page: org.jsoup.nodes.Document) = page.select("li.b_algo")

    override fun hit(block: Element): SearchHit? {
        val link = block.selectFirst("h2 a[href]") ?: return null
        val href = link.attr("href")
        // Adverts wear the same clothes as results on this page.
        if (href.startsWith(ADVERT)) return null
        val title = link.text().trim()
        if (title.isBlank()) return null
        return SearchHit(title = title, snippet = block.textOf("p"), url = href.unwrapped())
    }

    /**
     * Bing wraps outbound links in a redirect carrying the real one in base64.
     *
     * Unwrapped so the model is given the site rather than a tracking hop, and so a fetch
     * of the same URL later goes where it says it does.
     */
    private fun String.unwrapped(): String {
        if (!startsWith(REDIRECT)) return this
        val encoded = substringAfter("&u=", "").substringBefore('&')
        if (encoded.length <= PREFIX) return this
        return runCatching {
            val body = encoded.substring(PREFIX)
            val padded = body.padEnd(body.length + (PAD - body.length % PAD) % PAD, '=')
            String(Base64.getUrlDecoder().decode(padded))
        }.getOrDefault(this)
    }

    private companion object {
        const val ADVERT = "https://www.bing.com/aclick?"
        const val REDIRECT = "https://www.bing.com/ck/a?"

        /** The two characters Bing puts before the base64, which are not part of it. */
        const val PREFIX = 2
        const val PAD = 4
    }
}

/**
 * Google, which is the best index and the hardest to read.
 *
 * Offered because it is what people mean by searching, and marked as the one most likely to
 * refuse: Google serves a consent interstitial or a challenge to anything that does not look
 * like a browser it recognises, and from some networks it refuses regardless. That is what
 * the proxy setting is for, and it is not a guarantee. A refusal is reported as "could not
 * answer" so the next engine runs, which is the whole reason the chain exists.
 */
internal class GoogleProvider(client: OkHttpClient) : HtmlSearchProvider(client) {
    override val id = "google"
    override val label = "Google"

    override fun request(query: String) = Request.Builder()
        .url("https://www.google.com/search?q=${query.urlEncoded()}&num=20")
        .header("User-Agent", SEARCH_USER_AGENT_BROWSER)
        .header("Accept", "text/html")
        // Without this Google answers with the consent page instead of results.
        .header("Cookie", "CONSENT=YES+")
        .build()

    override fun results(page: org.jsoup.nodes.Document) = page.select("div[data-hveid]:has(h3)")

    override fun hit(block: Element): SearchHit? {
        val title = block.selectFirst("h3")?.text()?.trim().orEmpty()
        val url = block.selectFirst("a[href]")?.attr("href")?.unwrapped().orEmpty()
        if (title.isBlank() || !url.startsWith("http")) return null
        // The snippet is the last block of the result, which is the only stable way to find
        // it: the class names here are generated and change between responses.
        val snippet = block.select("div").lastOrNull()?.text()?.trim().orEmpty()
        return SearchHit(title = title, snippet = snippet.take(SNIPPET_CHARS), url = url)
    }

    /** Google sometimes hands back its own redirect rather than the destination. */
    private fun String.unwrapped(): String = if (startsWith("/url?q=")) {
        runCatching {
            URLDecoder.decode(
                removePrefix("/url?q=").substringBefore('&'),
                Charsets.UTF_8.name(),
            )
        }.getOrDefault(this)
    } else {
        this
    }

    private companion object {
        const val SNIPPET_CHARS = 400
    }
}

private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

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

/**
 * The general engines besides DuckDuckGo — Brave and Yahoo — each scraped from its own
 * results page. Bing and Google are not here: both were measured refusing phones outright.
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

        val hits = runCatching { parse(page, limit) }.getOrDefault(emptyList())

        // Never an empty list. See the note on this class.
        return hits.ifEmpty { null }
    }

    /** The read alone, visible so a captured page can pin it in a test. */
    internal fun parse(page: String, limit: Int): List<SearchHit> =
        results(Jsoup.parse(page)).mapNotNull { hit(it) }.take(limit)

    protected fun Element.textOf(selector: String): String =
        selectFirst(selector)?.text()?.trim().orEmpty()

    private companion object {
        /** Generous for a results page, and an end where a remote server decides the size. */
        const val MAX_PAGE_BYTES = 1L shl 20
    }
}

/**
 * Yahoo, which is Bing's index behind a door that actually opens for a phone.
 *
 * Bing's own results page answered 200 with zero parseable results on both of this
 * project's phones (and ddgs disabled its Bing engine for the same reason), while Yahoo
 * returned eleven to twenty-five relevant hits in about a second on both, measured by
 * `SearchEnginesOnDeviceTest` on the Dimensity and the Snapdragon on the same day. Same
 * index, working spelling of it.
 *
 * The real address hides in the tracking redirect: every organic anchor points at
 * `r.search.yahoo.com/...RU=<encoded url>/...`, and the `RU` segment is the destination.
 * Organic anchors are the ones carrying an `h3.title`; the site navigation carries none.
 */
internal class YahooProvider(client: OkHttpClient) : HtmlSearchProvider(client) {
    override val id = "yahoo"
    override val label = "Yahoo"

    override fun request(query: String): Request = Request.Builder()
        .url("https://search.yahoo.com/search?p=${query.urlEncoded()}")
        .header("User-Agent", SEARCH_USER_AGENT_BROWSER)
        .header("Accept", "text/html")
        .build()

    override fun results(page: org.jsoup.nodes.Document): List<Element> =
        page.select("a[href*=/RU=]").filter { it.selectFirst("h3.title") != null }

    override fun hit(block: Element): SearchHit? {
        val destination = REDIRECT_TARGET.find(block.attr("href"))?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }
            ?: return null
        // The ad system resolves through Bing's click tracker; a result that does is paid
        // placement, not an answer.
        if (!destination.startsWith("http") || ADVERT in destination) return null
        val title = block.textOf("h3.title").ifEmpty { return null }
        // The snippet lives beside the anchor, not inside it. Walking a few parents up
        // finds the result's own card; further than that would reach the page and pair
        // every result with the first snippet on it.
        val snippet = block.parents().take(PARENT_HOPS)
            .firstNotNullOfOrNull { parent ->
                parent.selectFirst("p[class*=fc-dustygray]")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            .orEmpty()
        return SearchHit(title = title, snippet = snippet, url = destination)
    }

    private companion object {
        val REDIRECT_TARGET = Regex("""/RU=([^/]+)/""")
        const val ADVERT = "bing.com/aclick"
        const val PARENT_HOPS = 4
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

private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

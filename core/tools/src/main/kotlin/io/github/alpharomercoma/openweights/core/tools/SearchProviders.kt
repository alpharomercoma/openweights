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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder

/** One result, in the shape the model is shown regardless of who answered. */
data class SearchHit(val title: String, val snippet: String, val url: String)

/**
 * Where `web_search` looks.
 *
 * A seam rather than a hardcoded site, because which search engine someone trusts is
 * their decision and not one an app should make for them by naming a tool after it.
 *
 * [search] returns null for "this provider could not answer", which is deliberately not
 * the same as an empty list. A provider that is unconfigured, rate limited or blocked has
 * told us nothing about the web, and reporting that as "no results" is the failure mode
 * that made scraped search useless here: the model was told the web was empty and
 * confidently said so.
 */
interface SearchProvider {
    val id: String

    /** Shown in settings. */
    val label: String

    /** True when this provider has everything it needs to be tried. */
    val isConfigured: Boolean

    suspend fun search(query: String, limit: Int): List<SearchHit>?
}

/**
 * DuckDuckGo, with no key and no account.
 *
 * The default, because it is the only general web search measured to answer without one.
 * Getting there took reading what the `ddgs` package actually does, since the obvious
 * request does not work: a plain GET, or a POST without a browser agent and a referer,
 * comes back as HTTP 202 and a page with no results at all.
 *
 * What works is the two step the site itself performs. The first request collects the
 * cookies the search page sets; the second posts the query to the no-JavaScript endpoint
 * carrying them. Measured three for three at that, and zero for five without the first
 * step, which is why the extra round trip is here rather than optimised away.
 *
 * The 202 is the trap worth naming: it is a success status carrying an empty page, so a
 * parser that only counts results cannot tell "nothing matched" from "you are being rate
 * limited". That is reported as a failure, never as an empty result set, so the next
 * provider gets a turn instead of the model being told the web is empty.
 */
class DuckDuckGoProvider(httpClient: OkHttpClient) : SearchProvider {
    override val id = "duckduckgo"
    override val label = "DuckDuckGo"
    override val isConfigured = true

    /**
     * Its own cookie jar, held in memory and never written to disk.
     *
     * The cookies are what makes the second request work, and they are also a handle on
     * whoever is searching, so they live no longer than the process.
     */
    private val client = httpClient.newBuilder().cookieJar(MemoryCookieJar()).build()

    override suspend fun search(query: String, limit: Int): List<SearchHit>? {
        val landing = HOME.toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        // Only for its cookies. A failure here is not fatal: the POST is still worth trying.
        client.textOrNull(landing) { it.header("Accept", "text/html") }

        val form = FormBody.Builder().add("q", query).build()
        val request = Request.Builder()
            .url(HTML_ENDPOINT)
            .post(form)
            .header("User-Agent", SEARCH_USER_AGENT_BROWSER)
            .header("Referer", "$HOME/")
            .header("Accept", "text/html")
            .build()

        val page = runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code != HTTP_OK) null else response.body.string()
            }
        }.getOrNull() ?: return null

        val hits = parseDuckDuckGo(page, limit)
        // Zero results from this endpoint is almost always the rate limiter rather than an
        // empty web, so it is reported as "could not answer" and the next provider runs.
        return hits.ifEmpty { null }
    }

    internal companion object {
        const val HOME = "https://duckduckgo.com"
        const val HTML_ENDPOINT = "https://html.duckduckgo.com/html/"
        const val HTTP_OK = 200

        private val RESULT = Regex(
            """result__a[^>]*href="([^"]+)"[^>]*>(.*?)</a>.*?result__snippet[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val TAG = Regex("<[^>]+>")

        fun parseDuckDuckGo(page: String, limit: Int): List<SearchHit> =
            RESULT.findAll(page).take(limit).map { match ->
                val (href, title, snippet) = match.destructured
                SearchHit(
                    title = title.stripTags(),
                    snippet = snippet.stripTags(),
                    url = href.unwrapRedirect(),
                )
            }.toList()

        private fun String.stripTags(): String = TAG.replace(this, "").unescapeEntities().trim()

        /**
         * The destination behind a `/l/?uddg=` wrapper.
         *
         * The endpoint returns direct links most of the time and wrapped ones sometimes.
         * Handing the model a redirector as the source of a fact would make every citation
         * point at DuckDuckGo instead of at whoever wrote it.
         */
        private fun String.unwrapRedirect(): String {
            if (!contains("uddg=")) return this
            val encoded = substringAfter("uddg=").substringBefore("&")
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(this)
        }

        private fun String.unescapeEntities(): String = this
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }
}

/** Cookies for one process and no longer, so a search leaves nothing behind. */
private class MemoryCookieJar : CookieJar {
    private val jar = mutableMapOf<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        jar[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        // Shared across duckduckgo.com and html.duckduckgo.com, which is the whole point:
        // the cookies the search page sets are what the html endpoint checks for.
        jar.filterKeys { url.host.endsWith(it) || it.endsWith(url.host) }.values.flatten()
}

/**
 * A SearXNG instance, the user's own or one they trust.
 *
 * The whole appeal is that no key is involved and the instance is the user's choice.
 * Public instances mostly rate limit anonymous JSON requests, so this is documented as
 * "point it at your own" rather than shipped with a default host: a default that returns
 * 429 to everyone would be worse than none.
 */
class SearxProvider(private val httpClient: OkHttpClient, private val baseUrl: String) :
    SearchProvider {
    override val id = "searxng"
    override val label = "SearXNG"
    override val isConfigured get() = baseUrl.isNotBlank() && baseUrl.toHttpUrlOrNull() != null

    override suspend fun search(query: String, limit: Int): List<SearchHit>? {
        val root = baseUrl.toHttpUrlOrNull() ?: return null
        val url = root.newBuilder()
            .addPathSegments("search")
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .build()
        val body = httpClient.textOrNull(url) ?: return null
        val results = runCatching {
            Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
        }.getOrNull() ?: return null
        return results.toHits(limit, title = "title", snippet = "content", url = "url")
    }
}

/** The body of a successful response, or null for anything else. */
private inline fun OkHttpClient.textOrNull(
    url: HttpUrl,
    headers: (Request.Builder) -> Unit = {},
): String? {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", SEARCH_USER_AGENT)
        .header("Accept", "application/json")
        .also(headers)
        .build()
    return runCatching {
        newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    }.getOrNull()
}

/** Reads the three fields every engine has, whatever it calls them. */
private fun JsonArray.toHits(
    limit: Int,
    title: String,
    snippet: String,
    url: String,
): List<SearchHit> = mapNotNull { element ->
    val fields = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
    val link = fields[url]?.text() ?: return@mapNotNull null
    SearchHit(
        title = fields[title]?.text() ?: link,
        snippet = fields[snippet]?.text().orEmpty(),
        url = link,
    )
}.take(limit)

private fun kotlinx.serialization.json.JsonElement.text(): String? =
    runCatching { jsonPrimitive.content }.getOrNull()

/** Identifies the client, which is what Wikimedia asks for and what Brave logs. */
const val SEARCH_USER_AGENT =
    "OpenWeights/0.1 (https://github.com/alpharomercoma/openweights)"

/**
 * A browser's agent, sent only to DuckDuckGo's no-JavaScript endpoint.
 *
 * Not a disguise for its own sake: that endpoint exists for browsers without JavaScript
 * and answers 202 with an empty page to anything that does not look like one. Every other
 * provider here is an API and gets told honestly what we are.
 */
const val SEARCH_USER_AGENT_BROWSER =
    "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/140.0.0.0 Mobile Safari/537.36"

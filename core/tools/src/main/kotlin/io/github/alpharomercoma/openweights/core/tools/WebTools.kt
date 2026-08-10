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

import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads one argument out of whatever JSON the model produced.
 *
 * Small models are inconsistent about argument names and about whether they quote numbers,
 * so this accepts any of several spellings rather than insisting on one. Returning null
 * lets the tool answer with a sentence the model can act on instead of throwing.
 */
internal fun ToolCall.argument(vararg names: String): String? {
    val root = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
        ?: return null
    for (name in names) {
        val value = root[name]?.jsonPrimitive?.contentOrNull()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    runCatching { content }.getOrNull()

/**
 * Searches an encyclopedia.
 *
 * Wikipedia's own API, and not a search engine, after measuring both. Scraping
 * DuckDuckGo's keyless HTML endpoint worked in a first test and then returned nothing for
 * ten consecutive requests once it had seen a burst from one address: it rate limits by
 * serving a page with no results rather than an error, so the tool cannot tell "nothing
 * matched" from "you have been blocked" and would quietly tell the model the web is empty.
 * A search that fails silently is worse than no search.
 *
 * Wikipedia answered five out of five, in under a second, and returns article intros in
 * the same call, so one round trip is enough to answer from. What it costs is breadth:
 * this is the wrong tool for prices, news and anything from this week, and the description
 * says so, because a model that knows a tool's limits asks for it less often when it will
 * not help.
 *
 * General web search needs a key. The seam for one is [SearchProvider]; nothing keyed
 * ships, because shipping our key would be giving it away and demanding the user's would
 * mean the feature does not work until they find one.
 */
@Singleton
class WebSearchTool @Inject constructor(private val httpClient: OkHttpClient) : Tool {
    override val definition = ToolDefinition(
        name = "search_wikipedia",
        description = "Search Wikipedia and return the opening of each matching article. " +
            "Use it for people, places, organisations, history, science and definitions. " +
            "It will not have this week's news, prices, or anything very recent.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "What to look up, as you would type it into a search box"
                }
              },
              "required": ["query"]
            }
        """.trimIndent(),
    )

    override suspend fun run(call: ToolCall): String = withContext(Dispatchers.IO) {
        val query = call.argument("query", "q", "search", "input", "topic")
            ?: return@withContext "No query was given. Call search_wikipedia again with a query."

        val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            // generator=search with prop=extracts is what makes this one round trip: the
            // hits and the text to answer from arrive together.
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", query)
            .addQueryParameter("gsrlimit", MAX_RESULTS.toString())
            .addQueryParameter("prop", "extracts")
            .addQueryParameter("exintro", "1")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("exlimit", MAX_RESULTS.toString())
            .build()

        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

        val body = runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string()
            }
        }.getOrNull()
            ?: return@withContext "Wikipedia could not be reached. The device may be offline."

        val results = parseResults(body)
        if (results.isEmpty()) {
            return@withContext "Wikipedia has no article matching \"$query\"."
        }

        results.joinToString("\n\n") { result ->
            "${result.title}\n${result.extract}\n${result.url}"
        }
    }

    internal data class Result(val title: String, val extract: String, val url: String)

    internal companion object {
        /** Wikimedia asks for a descriptive agent that identifies the client. */
        const val USER_AGENT = "OpenWeights/0.1 (https://github.com/alpharomercoma/openweights)"

        /** Three articles is enough to answer from and small enough for a phone's context. */
        const val MAX_RESULTS = 3

        /** Long enough to answer from, short enough that three of them still fit. */
        const val MAX_EXTRACT_CHARS = 900

        fun parseResults(payload: String): List<Result> {
            val pages = runCatching {
                Json.parseToJsonElement(payload)
                    .jsonObject["query"]?.jsonObject
                    ?.get("pages")?.jsonObject
            }.getOrNull() ?: return emptyList()

            return pages.values.mapNotNull { page ->
                val fields = page.jsonObject
                val title =
                    fields["title"]?.jsonPrimitive?.contentOrNull() ?: return@mapNotNull null
                val extract = fields["extract"]?.jsonPrimitive?.contentOrNull().orEmpty()
                if (extract.isBlank()) return@mapNotNull null
                Result(
                    title = title,
                    extract = extract.take(MAX_EXTRACT_CHARS),
                    url = "https://en.wikipedia.org/wiki/" + title.replace(' ', '_'),
                )
            }
        }
    }
}

/**
 * Reads one page.
 *
 * Search gives a model titles and a sentence each; this is how it reads the thing it found.
 * Markup is stripped rather than rendered, because a phone-sized context window cannot
 * afford navigation menus, and truncated hard, because one long page can fill the whole
 * window and leave no room for the answer.
 */
@Singleton
class FetchUrlTool @Inject constructor(private val httpClient: OkHttpClient) : Tool {
    override val definition = ToolDefinition(
        name = "fetch_url",
        description = "Fetch a public web page and return its readable text. Use it to " +
            "read a page whose address you already have, for example one that " +
            "search_wikipedia returned.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "The full https address of the page to read"
                }
              },
              "required": ["url"]
            }
        """.trimIndent(),
    )

    override suspend fun run(call: ToolCall): String = withContext(Dispatchers.IO) {
        val url = call.argument("url", "link", "address", "input")
            ?: return@withContext "No URL was given. Call fetch_url again with a url."

        // https only, and the app disables cleartext anyway, so this refusal is the honest
        // message rather than a network error the model cannot interpret.
        if (!url.startsWith("https://")) {
            return@withContext "Only https addresses can be read. Got: $url"
        }

        val request = Request.Builder().url(
            url,
        ).header("User-Agent", WebSearchTool.USER_AGENT).build()

        val body = runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use "HTTP ${response.code}"
                response.body.string().readable()
            }
        }.getOrNull()
            ?: return@withContext "That page could not be read. The device may be offline."

        if (body.length <= MAX_CHARS) body else body.take(MAX_CHARS) + "\n[truncated]"
    }

    private companion object {
        /** About a thousand tokens: enough to answer from, small enough to leave room. */
        const val MAX_CHARS = 4_000

        /** Read at most this much, whatever the page claims about its length. */
        const val MAX_BYTES = 512 * 1024

        /** Content types worth handing to a language model. */
        val TEXTUAL = listOf("text/", "application/json", "application/xml", "application/xhtml")
    }
}

/** Everything between tags, with the tags and the unreadable parts taken out. */
private fun String.readable(): String = this
    .replace(Regex("""(?is)<(script|style|nav|header|footer)[^>]*>.*?</\1>"""), " ")
    .stripTags()

private fun String.stripTags(): String = this
    .replace(Regex("<[^>]+>"), " ")
    .let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString() }
    .replace(Regex("""\s+"""), " ")
    .trim()

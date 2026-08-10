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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
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
 * Searches the web.
 *
 * DuckDuckGo's HTML endpoint, because it needs no key and no account. That is the whole
 * reason: a keyed search API would mean either shipping our key, which is a giveaway, or
 * asking every user to get one before the feature works at all. The cost is that this
 * parses a page rather than an API, so it can break when the page changes, and the tool
 * says so plainly when it finds nothing rather than pretending the web is empty.
 */
@Singleton
class WebSearchTool @Inject constructor(private val httpClient: OkHttpClient) : Tool {
    override val definition = ToolDefinition(
        name = "web_search",
        description = "Search the web and return the top results with titles, URLs and " +
            "short snippets. Use it for anything current, anything specific, or anything " +
            "you are not sure about.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "What to search for, as you would type it into a search box"
                }
              },
              "required": ["query"]
            }
        """.trimIndent(),
    )

    override suspend fun run(call: ToolCall): String = withContext(Dispatchers.IO) {
        val query = call.argument("query", "q", "search", "input")
            ?: return@withContext "No query was given. Call web_search again with a query."

        val request = Request.Builder()
            .url(
                "https://lite.duckduckgo.com/lite/?q=" + java.net.URLEncoder.encode(query, "UTF-8"),
            )
            // A desktop agent string: the mobile page returns a different layout and this
            // parser is written against one of them.
            .header("User-Agent", USER_AGENT)
            .build()

        val body = runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string()
            }
        }.getOrNull()
            ?: return@withContext "The search could not be reached. The device may be offline."

        val results = parseResults(body).take(MAX_RESULTS)
        if (results.isEmpty()) {
            return@withContext "No results were found for \"$query\"."
        }

        results.mapIndexed { index, result ->
            "${index + 1}. ${result.title}\n   ${result.url}\n   ${result.snippet}"
        }.joinToString("\n")
    }

    internal data class Result(val title: String, val url: String, val snippet: String)

    internal companion object {
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) OpenWeights"
        const val MAX_RESULTS = 5

        private val LINK = Regex(
            """<a[^>]+class="result-link"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val SNIPPET = Regex(
            """<td[^>]*class="result-snippet"[^>]*>(.*?)</td>""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /**
         * Pulls results out of the page.
         *
         * Links come back wrapped in a redirect with the real address in a `uddg`
         * parameter, so they are unwrapped: a model handed a redirect cannot tell which
         * site it is about to read.
         */
        fun parseResults(html: String): List<Result> {
            val links = LINK.findAll(html).toList()
            val snippets = SNIPPET.findAll(html).map { it.groupValues[1].stripTags() }.toList()
            return links.mapIndexed { index, match ->
                Result(
                    title = match.groupValues[2].stripTags(),
                    url = unwrap(match.groupValues[1]),
                    snippet = snippets.getOrElse(index) { "" },
                )
            }.filter { it.title.isNotBlank() && it.url.isNotBlank() }
        }

        private fun unwrap(href: String): String {
            val marker = "uddg="
            val start = href.indexOf(marker)
            if (start < 0) return href.trim()
            val raw = href.substring(start + marker.length).substringBefore("&")
            return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim()
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
        description = "Fetch a web page and return its readable text. Use it after " +
            "web_search to read a result properly.",
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

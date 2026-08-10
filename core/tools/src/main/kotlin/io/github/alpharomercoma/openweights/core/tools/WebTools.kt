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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
class WebSearchTool @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settings: SearchSettings,
) : Tool {
    override val definition = ToolDefinition(
        name = "web_search",
        // Named for what it does, not for where it looks. The name is the strongest hint a
        // model gets: while this was called search_wikipedia, replies said "Wikipedia" for
        // questions that had nothing to do with an encyclopedia, and the model wrote as
        // though the rest of the web were out of reach.
        description = "Search the web and return the best matching pages with a summary " +
            "of each. Use it whenever an answer depends on a fact you do not already " +
            "have: people, places, organisations, products, events, definitions.",
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
            ?: return@withContext "No query was given. Call web_search again with a query."

        val providers = settings.providers(httpClient)
        // Each in turn until one answers. A provider returns null when it could not answer
        // rather than an empty list, so being rate limited moves on to the next one instead
        // of telling the model the web has nothing on the subject.
        val answered = providers.firstNotNullOfOrNull { provider ->
            provider.search(query, MAX_RESULTS)?.takeIf { it.isNotEmpty() }?.let { provider to it }
        }

        val (provider, results) = answered
            ?: return@withContext "No search provider could answer. The device may be " +
                "offline, or the search may be rate limited. Say so rather than guessing."

        // Framed as material, not as a menu. A small model handed three bare titles reads
        // them as options and asks which one to open, which is how "what is the Eiffel
        // Tower" came back as "how about I fetch the page for Gustave Eiffel". The first
        // result is the best match and saying so is what stops it picking the third.
        buildString {
            append("Results for \"").append(query).append("\" from ").append(provider.label)
            append(", best match first. Answer the question using these. ")
            append("Do not ask which one to read.\n")
            results.forEachIndexed { index, result ->
                append("\n[").append(index + 1).append("] ").append(result.title).append('\n')
                append(result.snippet.take(MAX_EXTRACT_CHARS)).append('\n')
                append(result.url).append('\n')
            }
        }
    }

    override fun callFor(question: String): ToolCall? {
        if (question.isBlank()) return null
        val arguments = buildJsonObject { put("query", JsonPrimitive(question)) }
        return ToolCall(id = "", name = definition.name, argumentsJson = arguments.toString())
    }

    internal companion object {
        /** Three results is enough to answer from and small enough for a phone's context. */
        const val MAX_RESULTS = 3

        /** Long enough to answer from, short enough that three of them still fit. */
        const val MAX_EXTRACT_CHARS = 900
    }
}

class FetchUrlTool @Inject constructor(private val httpClient: OkHttpClient) : Tool {
    override val definition = ToolDefinition(
        name = "fetch_url",
        description = "Fetch a public web page and return its readable text. Use it to " +
            "read a page whose address you already have, for example one that " +
            "web_search returned.",
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

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SEARCH_USER_AGENT)
            .build()

        val body = runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use "HTTP ${response.code}"

                // Both of these guards existed as constants and neither was applied, so the
                // tool would pull a response of any size and any type into memory and then
                // try to read it as prose. The model chooses this address, which is what
                // makes it worth checking: a link in a search result can point at a video,
                // an archive, or a page that never stops.
                val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
                if (type != null && TEXTUAL.none { type.startsWith(it) }) {
                    return@use "That address is $type, which is not text. Nothing to read."
                }
                // peekBody stops at the limit rather than after it: the bytes past it are
                // never buffered, so a page with no end cannot exhaust the heap.
                response.peekBody(MAX_BYTES.toLong()).string().readable()
            }
        }.getOrNull()
            ?: return@withContext "That page could not be read. The device may be offline."

        if (body.length <= MAX_CHARS) body else body.take(MAX_CHARS) + "\n[truncated]"
    }

    private companion object {
        /** About a thousand tokens: enough to answer from, small enough to leave room. */
        const val MAX_CHARS = 4_000

        /**
         * Read at most this much, whatever the page claims about its length.
         *
         * Half a megabyte is far more than the four thousand characters that survive
         * trimming, and the gap is deliberate: the cap is there to bound the download, not
         * to choose the excerpt, and HTML spends most of its bytes on markup this strips.
         */
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

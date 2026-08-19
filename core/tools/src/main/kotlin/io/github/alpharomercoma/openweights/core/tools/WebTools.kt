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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
 * DuckDuckGo, scraped, because there is no keyless general web search that is not a
 * scraper. That was measured rather than assumed, and the note in docs/research has the
 * table: the lite and html endpoints answer 202 to a plain GET, the Instant Answer API is
 * empty for anything that is not a dictionary word, public SearXNG instances answer 403
 * because they ship with the JSON format disabled, and everything else wants a key. What
 * works is the two step the `ddgs` package uses, cookies first and then a POST carrying a
 * Referer, which is what [DuckDuckGoProvider] does.
 *
 * This KDoc used to describe Wikipedia and argue against DuckDuckGo, on the strength of an
 * early test where the scrape returned nothing ten times in a row. That was rate limiting
 * being mistaken for a dead end. Wikipedia went because it was a hardcoded site nobody
 * could see or switch off, and because of what it did to answers: asked about a stranger
 * it returned an unrelated senator rather than nothing.
 *
 * One source is the standing weakness. A provider that is rate limited returns null rather
 * than an empty list, so the chain can tell "nothing matched" from "you have been blocked"
 * and move on, but today there is nothing to move on to. More providers behind
 * [SearchProvider] is the work that fixes it.
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
        // The old wording said to use it "whenever an answer depends on a fact you do not
        // already have", and listed definitions among the examples. That argues with the
        // system message beside it, which says to answer settled facts directly, and a model
        // told two things follows the one attached to the tool it is looking at. Gemma 3 1B
        // over-called on twelve of twelve chances with that pairing in place.
        description = "Search the web for something that changes or is recent: news, " +
            "prices, schedules, results, or a named person, product or organisation. " +
            "Not for definitions, translations, or facts that do not change.",
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

    /**
     * What comes back is text somebody else wrote, and that is now load bearing.
     *
     * It was never declared, because fetching a page carried `alwaysAsk` and so was gated
     * whatever this said. With that gone, this flag is the only thing standing between a
     * page holding a literal tool call and a model obligingly repeating it: the runner
     * remembers that untrusted text has entered the turn, and anything wanting to leave
     * the device afterwards is approved by hand.
     */
    override val returnsUntrustedText: Boolean = true

    override val leavesTheDevice: Boolean = true

    override suspend fun run(call: ToolCall): String = withContext(Dispatchers.IO) {
        val query = call.argument("query", "q", "search", "input", "topic")
            ?: return@withContext "No query was given. Call web_search again with a query."
        if (query.length > MAX_QUERY_CHARS) {
            return@withContext "That is too long to search for. Search for a few words " +
                "rather than a passage."
        }

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

    internal companion object {
        /**
         * Longer than any question and shorter than a document.
         *
         * A search box takes a few words. Anything approaching a paragraph is either a model
         * that has misunderstood the tool or a passage out of a file on its way to a
         * stranger, and neither is worth sending. It is a bound on the damage rather than a
         * defence: a short secret still fits, which is why it is not the only control here.
         */
        const val MAX_QUERY_CHARS = 120

        /** Three results is enough to answer from and small enough for a phone's context. */
        const val MAX_RESULTS = 3

        /** Long enough to answer from, short enough that three of them still fit. */
        const val MAX_EXTRACT_CHARS = 900
    }
}

class FetchUrlTool @Inject constructor(httpClient: OkHttpClient) : Tool {
    /**
     * The shared client, refusing to dial anything off the public internet.
     *
     * newBuilder keeps the connection pool and dispatcher, so this costs nothing but the
     * resolver. It is only this tool that needs it: every other request in the app goes to
     * an address the app chose, and this is the one the model chooses. See [PublicOnlyDns].
     */
    private val httpClient: OkHttpClient = httpClient.newBuilder()
        .dns(PublicOnlyDns())
        // Followed by hand in [run] rather than by OkHttp, and this is a security boundary
        // rather than a preference. The address checks below run on the address the user
        // approved; a redirect is a second address, chosen by the page rather than by
        // anyone here. OkHttp would dial it without asking, and [PublicOnlyDns] cannot
        // cover it either, because OkHttp skips its resolver entirely for an address
        // written as digits. So a public page that answers "302 Location:
        // https://192.168.1.1/admin" reached the router on the user's own network, and the
        // reply summarising what it found there was the whole exploit.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * What comes back is text somebody else wrote, and that is now load bearing.
     *
     * It was never declared, because fetching a page carried `alwaysAsk` and so was gated
     * whatever this said. With that gone, this flag is the only thing standing between a
     * page holding a literal tool call and a model obligingly repeating it: the runner
     * remembers that untrusted text has entered the turn, and anything wanting to leave
     * the device afterwards is approved by hand.
     */
    override val returnsUntrustedText: Boolean = true

    override val leavesTheDevice: Boolean = true

    /**
     * The address comes from the model, which is what makes this the gated one.
     *
     * A page can name its own server here and read what arrives. It cannot do that through
     * a search, which goes to the provider the app is configured with whatever the query
     * says.
     */
    override val sendsWhereTheModelSays: Boolean = true

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

        // Parsed rather than pattern matched. The checks below are about the host, and the
        // host is not the part of the string it looks like: everything before an @ is
        // userinfo and goes nowhere, so "https://example.com@10.0.0.1/" reads as example.com
        // to anything working on the text.
        var next = url.trim().toHttpUrlOrNull()
            ?: return@withContext "That is not an address that can be read. Got: $url"

        // Every hop, not only the address the user approved. See [refuseAddress].
        var hops = 0
        while (true) {
            refuseAddress(next)?.let { return@withContext it }

            val request = Request.Builder()
                .url(next)
                .header("User-Agent", SEARCH_USER_AGENT)
                .build()

            val hop = runCatching {
                httpClient.newCall(request).execute().use { response ->
                    redirectTarget(response, next)?.let { return@use Hop.Moved(it) }
                    Hop.Read(textOf(response))
                }
            }.getOrNull()
                ?: return@withContext "That page could not be read. The device may be offline."

            when (hop) {
                is Hop.Read -> {
                    val body = hop.text
                    return@withContext if (body.length <= MAX_CHARS) {
                        body
                    } else {
                        body.take(MAX_CHARS) + "\n[truncated]"
                    }
                }
                is Hop.Moved -> {
                    if (++hops > MAX_HOPS) {
                        return@withContext "That address redirected more than $MAX_HOPS " +
                            "times, so it was not read."
                    }
                    next = hop.to
                }
            }
        }
        // Unreachable: every branch of the loop returns.
        @Suppress("UNREACHABLE_CODE")
        return@withContext "That page could not be read."
    }

    /**
     * What is worth reading in a response, or why nothing is.
     *
     * Both of these guards existed as constants and neither was applied, so the tool would
     * pull a response of any size and any type into memory and then try to read it as
     * prose. The model chooses this address, which is what makes it worth checking: a link
     * in a search result can point at a video, an archive, or a page that never stops.
     */
    private fun textOf(response: Response): String {
        if (!response.isSuccessful) return "HTTP ${response.code}"

        val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
        if (type != null && TEXTUAL.none { type.startsWith(it) }) {
            return "That address is $type, which is not text. Nothing to read."
        }
        // peekBody stops at the limit rather than after it: the bytes past it are never
        // buffered, so a page with no end cannot exhaust the heap.
        return response.peekBody(MAX_BYTES.toLong()).string().readable()
    }

    /** One step of a fetch: either something to read, or somewhere else to look. */
    private sealed interface Hop {
        data class Read(val text: String) : Hop
        data class Moved(val to: HttpUrl) : Hop
    }

    private companion object {
        /**
         * How many redirects one address may take before it is given up on.
         *
         * Five is what a browser allows for the same reason: a chain longer than that is a
         * loop or a tracker, and every hop is another address this tool has to be sure of.
         */
        const val MAX_HOPS = 5

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

/**
 * Why this address will not be dialled, or null when it will.
 *
 * Its own function because it has to run on every hop rather than once. The version before
 * this ran the same two checks inline, on the address the user approved, and then handed the
 * call to a client that followed redirects on its own: a page could answer "302 Location:
 * https://192.168.1.1/admin" and be dialled with neither check applied. `PublicOnlyDns` does
 * not close that either, because OkHttp skips its resolver for an address written as digits,
 * which is exactly the form an attack uses.
 *
 * Internal rather than private because it is also the only way this can be tested. The guard
 * refuses loopback, and loopback is where a test server necessarily lives, so there is no
 * address a fetch could be exercised against end to end. A test that cannot exist is worse
 * than a test that checks the rule directly.
 */
internal fun refuseAddress(url: HttpUrl): String? {
    // https only, and the app disables cleartext anyway, so this refusal is the honest
    // message rather than a network error the model cannot interpret. Asked of the parsed
    // scheme, which is lower-cased for us: startsWith("https://") told a model that wrote
    // HTTPS the app only reads https, which reads as nonsense.
    if (!url.isHttps) return "Only https addresses can be read. Got: $url"

    // The resolver cannot cover this. PublicOnlyDns sees names, and an address written as
    // digits goes straight to a socket without one, so every private address was reachable
    // by asking for it as a literal. On a phone that is the router, the printers beside it,
    // and whatever the carrier has on the same subnet.
    url.host.ipLiteralOrNull()?.takeUnless { it.isPublicAddress() }?.let {
        return "That address is not on the public internet, so it will not be read. " +
            "Ask for a public page instead."
    }
    return null
}

/**
 * Where a response says to look instead, or null when it is not sending us anywhere.
 *
 * Resolved against the address it came from, because a Location header is allowed to be
 * relative and a relative one cannot leave the host it arrived from. An absolute one can,
 * which is the case [refuseAddress] then has to answer for.
 */
internal fun redirectTarget(response: Response, from: HttpUrl): HttpUrl? {
    if (!response.isRedirect) return null
    return response.header("Location")?.let { from.resolve(it) }
}

/** Everything between tags, with the tags and the unreadable parts taken out. */
private fun String.readable(): String = withoutFurniture().stripTags()

/**
 * The page reduced to the part worth reading, still as markup.
 *
 * Separate from [stripTags] because that one decodes entities through the platform and so
 * cannot run off a device, while everything interesting here is string work that can be tested
 * on its own.
 */
internal fun String.withoutFurniture(): String = mainContent().replace(FURNITURE, " ")

/**
 * The part of the page somebody came to read, when the page says which part that is.
 *
 * What comes back is capped at four thousand characters, and on a modern page those first four
 * thousand are frequently not the article: they are a cookie notice, a
 * subscription offer, a menu of everything else on the site. The model then answers out of the
 * furniture, or says the page does not mention what it plainly does. Raising the ceiling is the
 * wrong fix, because every one of those characters is also decode time on a phone.
 *
 * So: if the document names its own content with `<article>` or `<main>`, take that and discard
 * the rest of the file. This is the one heuristic from the readability literature that needs no
 * document tree to apply, and it is the one that pays: it is a landmark the page author wrote
 * deliberately, not a guess about where the prose is.
 *
 * The largest match rather than the first, because an index page is a list of `<article>` cards
 * and the largest is the nearest thing on it to a body. `<article>` before `<main>` when it is
 * substantial, since `<main>` usually contains it along with whatever sits beside it. A page
 * with neither is returned whole and cleaned the way it always was.
 */
private fun String.mainContent(): String {
    val article = LANDMARKS.getValue("article").findAll(this).maxByOrNull { it.value.length }
    if (article != null && article.value.length >= ENOUGH_TO_BE_THE_BODY) return article.value
    val main = LANDMARKS.getValue("main").findAll(this).maxByOrNull { it.value.length }
    return main?.value ?: this
}

/**
 * The elements whose contents are never what was being looked for.
 *
 * Whole elements rather than a class-name guess: nesting of these is rare enough that a
 * non-greedy match ends where the element does, whereas the cookie banners and sidebars that
 * make up the rest of the furniture are `div`s indistinguishable by shape from the article. A
 * class-name pattern over `div` would remove the body of some page nobody here will ever see,
 * and this cannot be allowed to remove content: too little is a longer read, too much is a
 * confident wrong answer.
 */
private val FURNITURE = Regex(
    "(?is)<(script|style|nav|header|footer|aside|form|noscript|iframe|svg|template|dialog)" +
        """[^>]*>.*?</\1>""",
)

/** The two elements a page uses to say which part of it is the point. */
private val LANDMARKS = listOf("article", "main").associateWith {
    Regex("(?is)<$it\\b[^>]*>.*?</$it>")
}

/**
 * Below this an `<article>` is a teaser rather than a body, so the page is better read whole.
 *
 * A card on an index page carries a headline and a sentence. A short real article still clears
 * this, and one that does not loses nothing: falling through returns the fuller text.
 */
private const val ENOUGH_TO_BE_THE_BODY = 500

private fun String.stripTags(): String = this
    .replace(Regex("<[^>]+>"), " ")
    .let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString() }
    .replace(Regex("""\s+"""), " ")
    .trim()

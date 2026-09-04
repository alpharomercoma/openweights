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
import kotlinx.serialization.json.jsonObject
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
        // `as?` rather than `.jsonPrimitive`, which throws on an object or an array instead
        // of returning null. A small model writing {"query": {"text": "..."}} is not rare,
        // and the throw escaped the tool and killed the turn where the tool should simply
        // have said what argument it wanted.
        val value = (root[name] as? JsonPrimitive)?.contentOrNull()
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
    private val reachability: Reachability,
) : Tool {
    override val parallelSafe: Boolean = true

    /**
     * One step of several, not the whole errand: search is how a page is *found*, and the
     * page still has to be opened. With this false, a research step got exactly two
     * rounds — search, then a forced answer — while the research prompt beside it ordered
     * "change the query and search again", which is behavior the budget could not afford:
     * the retry spent the fetch round, the step failed its own evidence check, and two
     * such failures halted the goal. The user met that as "the loop is breaking".
     */
    override val chains: Boolean = true

    /** Not described to the model when it cannot work. See [Reachability]. */
    override val isAvailable: Boolean get() = reachability.isOnline()

    override val definition = ToolDefinition(
        name = NAME,
        // Named for what it does, not for where it looks. The name is the strongest hint a
        // model gets: while this was called search_wikipedia, replies said "Wikipedia" for
        // questions that had nothing to do with an encyclopedia, and the model wrote as
        // though the rest of the web were out of reach.
        // The old wording said to use it "whenever an answer depends on a fact you do not
        // already have", and listed definitions among the examples. That argues with the
        // system message beside it, which says to answer settled facts directly, and a model
        // told two things follows the one attached to the tool it is looking at. Gemma 3 1B
        // over-called on twelve of twelve chances with that pairing in place.
        // The exemplary form of this clause was not enough: measured on a 48 case suite,
        // "capital of Peru", "who wrote Pride and Prejudice", "translate thank you" and
        // four more all called this tool, and the model's own reasoning showed it deciding
        // to "verify with a web search" a fact it had already stated correctly. The list
        // is now exhaustive, and the double check is named as the thing not to do.
        description = "Search the web for what you cannot already know: what changed, " +
            "what is recent, or the present state of a named person, product or " +
            "organisation. Returns text; for pictures or clips use ${SearchMediaTool.NAME}. " +
            "Not for settled knowledge (definitions, translations, history, arithmetic) " +
            "and never to double check what you know: answer those yourself.",
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

    override suspend fun run(call: ToolCall): String = execute(call).text

    override suspend fun execute(call: ToolCall): ToolExecution = withContext(Dispatchers.IO) {
        val query = call.argument("query", "q", "search", "input", "topic")
            ?: return@withContext ToolExecution.failure(
                "No query was given. Call web_search again with a query.",
            )
        if (query.length > MAX_QUERY_CHARS) {
            return@withContext ToolExecution.failure(
                "That is too long to search for. Search for a few words rather than a passage.",
            )
        }

        // Through the proxy when one is set, and only here: see SearchSettings.proxy for
        // why this is scoped to search rather than applied to every request the app makes.
        val providers = settings.providers(settings.client(httpClient.forTools()))
        // Read per call rather than captured at construction: this tool is a singleton that
        // outlives the settings screen, so a value read once would be the value the process
        // started with and the slider would appear to do nothing until the app restarted.
        val (provider, results) = firstAnswer(providers, query, settings.resultCount)
            ?: return@withContext ToolExecution.failure(
                "No search provider could answer. The device may be offline, or the search " +
                    "may be rate limited. Say so rather than guessing.",
            )

        if (results.isEmpty()) {
            return@withContext ToolExecution(
                "No results for \"$query\" from ${provider.label}. The search worked and " +
                    "nothing matched: try different words, or say that nothing was found.",
            )
        }

        // Framed as material, not as a menu. A small model handed three bare titles reads
        // them as options and asks which one to open, which is how "what is the Eiffel
        // Tower" came back as "how about I fetch the page for Gustave Eiffel". The first
        // result is the best match and saying so is what stops it picking the third.
        webSearchSuccess(query, provider.label, results)
    }

    internal companion object {
        const val NAME = "web_search"

        /**
         * Each provider in turn until one answers, with whatever it said.
         *
         * A provider returns null when it could not answer rather than an empty list, so
         * being rate limited moves on to the next one instead of telling the model the web
         * has nothing on the subject. An empty list is the other thing entirely: the
         * provider looked and found nothing, and that is an answer. This used to skip it
         * too, so a query with no hits fell through every provider and came back as "the
         * device may be offline", which is a wrong report of a working search.
         */
        suspend fun firstAnswer(
            providers: List<SearchProvider>,
            query: String,
            limit: Int,
        ): Pair<SearchProvider, List<SearchHit>>? = providers.firstNotNullOfOrNull { provider ->
            provider.search(query, limit)?.let { provider to it }
        }

        /** Builds the model-facing result while keeping its source addresses structured. */
        fun webSearchSuccess(
            query: String,
            provider: String,
            results: List<SearchHit>,
        ): ToolExecution {
            val text = buildString {
                append("Results for \"").append(query).append("\" from ").append(provider)
                append(", best match first. Answer the question using these. ")
                append("Do not ask which one to read.\n")
                results.forEachIndexed { index, result ->
                    append("\n[").append(index + 1).append("] ").append(result.title).append('\n')
                    append(result.snippet.take(MAX_EXTRACT_CHARS)).append('\n')
                    append(result.url).append('\n')
                }
            }
            return ToolExecution(
                text = text,
                evidence = ToolEvidence.Search(
                    results.mapNotNull { it.url.toHttpUrlOrNull()?.toString() }.toSet(),
                ),
            )
        }

        /**
         * Longer than any question and shorter than a document.
         *
         * A search box takes a few words. Anything approaching a paragraph is either a model
         * that has misunderstood the tool or a passage out of a file on its way to a
         * stranger, and neither is worth sending. It is a bound on the damage rather than a
         * defence: a short secret still fits, which is why it is not the only control here.
         */
        const val MAX_QUERY_CHARS = 120

        /**
         * Long enough to answer from, short enough that several of them still fit.
         *
         * Sized against the default of three, which is 2,700 characters. At the widest
         * setting five of these is 4,500, still under a third of a 4k window and well
         * inside the smallest one the app will open.
         */
        const val MAX_EXTRACT_CHARS = 900
    }
}

class FetchUrlTool @Inject constructor(
    httpClient: OkHttpClient,
    private val reachability: Reachability,
    private val workspace: Workspace,
    private val artifacts: SessionArtifacts,
) : Tool {
    /**
     * Chains for the same reason search does: reading one page is rarely the whole
     * errand — the page names a better one, or the answer needs a second source, or what
     * was fetched goes to a file for run_script to work through.
     */
    override val chains: Boolean = true

    /** Not described to the model when it cannot work. See [Reachability]. */
    override val isAvailable: Boolean get() = reachability.isOnline()

    /**
     * The shared client, refusing to dial anything off the public internet.
     *
     * newBuilder keeps the connection pool and dispatcher, so this costs nothing but the
     * resolver. It is only this tool that needs it: every other request in the app goes to
     * an address the app chose, and this is the one the model chooses. See [PublicOnlyDns].
     */
    private val httpClient: OkHttpClient = httpClient.forTools().newBuilder()
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
        name = NAME,
        description = "Fetch a public web page and return its readable text, when you were " +
            "given the address. Not for finding a page, and not when a search result " +
            "already answers it. Pass find to search a long page for what you actually " +
            "need instead of reading its opening.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "The full https address of the page to read"
                },
                "save_to": {
                  "type": "string",
                  "description": "Optional file path to save the page's text into the shared folder for scripts"
                },
                "find": {
                  "type": "string",
                  "description": "Optional. A word, phrase or regular expression to look for. Only the parts of the page that match it come back, with a little text around each, instead of the start of the page"
                }
              },
              "required": ["url"]
            }
        """.trimIndent(),
    )

    override suspend fun run(call: ToolCall): String = execute(call).text

    override suspend fun execute(call: ToolCall): ToolExecution = withContext(Dispatchers.IO) {
        val url = call.argument("url", "link", "address", "input")
            ?: return@withContext ToolExecution.failure(
                "No URL was given. Call fetch_url again with a url.",
            )

        // Parsed rather than pattern matched. The checks below are about the host, and the
        // host is not the part of the string it looks like: everything before an @ is
        // userinfo and goes nowhere, so "https://example.com@10.0.0.1/" reads as example.com
        // to anything working on the text.
        var next = url.trim().toHttpUrlOrNull()
            ?: return@withContext ToolExecution.failure(
                "That is not an address that can be read. Got: $url",
            )
        val requested = next.toString()

        // Every hop, not only the address the user approved. See [refuseAddress].
        var hops = 0
        while (true) {
            (refuseAddress(next) ?: walledGardenRefusal(next))
                ?.let { return@withContext ToolExecution.failure(it) }

            val request = Request.Builder()
                .url(next)
                .header("User-Agent", SEARCH_USER_AGENT)
                .build()

            val hop = runCatching {
                httpClient.newCall(request).await().use { response ->
                    redirectTarget(response, next)?.let { return@use Hop.Moved(it) }
                    Hop.Read(textOf(response))
                }
            }.getOrNull()
                ?: return@withContext ToolExecution.failure(
                    "That page could not be read. The device may be offline.",
                )

            when (hop) {
                is Hop.Read ->
                    return@withContext readOutcome(hop.page, requested, next, call)
                is Hop.Moved -> {
                    if (++hops > MAX_HOPS) {
                        return@withContext ToolExecution.failure(
                            "That address redirected more than $MAX_HOPS times, so it was not read.",
                        )
                    }
                    next = hop.to
                }
            }
        }
        // Unreachable: every branch of the loop returns.
        @Suppress("UNREACHABLE_CODE")
        return@withContext ToolExecution.failure("That page could not be read.")
    }

    /** What a page that actually answered becomes, once its text has been looked at. */
    private suspend fun readOutcome(
        page: PageText,
        requested: String,
        finalUrl: HttpUrl,
        call: ToolCall,
    ): ToolExecution = when {
        !page.successful -> ToolExecution.failure(page.text)
        // A page that answered and left nothing to read is almost always one that
        // builds itself in the browser: the file holds a script and an empty div, and
        // the words arrive later from somewhere this cannot follow. Returning the
        // empty string said none of that, and a model handed nothing reports that the
        // page does not mention the thing, which is a wrong answer rather than a
        // missing one.
        page.text.isBlank() -> ToolExecution.failure(
            "That page has no readable text in it. It is probably built in " +
                "the browser, so there is nothing in the file to read. Try a " +
                "different source.",
        )
        else -> {
            // Searched before anything else looks at the text, and against the whole of it
            // rather than the four thousand characters that survive the cut. That is the
            // point of asking for one: what a long page says about a particular thing is
            // rarely in its opening, and reading the opening was all this tool could do.
            val find = call.argument("find", "pattern", "search", "contains", "grep")
                ?.takeIf { it.isNotBlank() }
            val saveTo = call.argument("save_to", "saveTo", "save")
            if (find != null) {
                matchOutcome(page.text, find, requested, finalUrl)
            } else if (saveTo != null && workspace.isReady && workspace.acceptsNewFiles) {
                // Saved whole, summarised briefly: a page can be far larger than the
                // context window, and the sandbox reading the file is how the model
                // works through what the conversation could never hold.
                //
                // Into a new file, or over one this session made. This replaced whatever
                // sat at the path, which made a fetch the user approved for its address
                // — or ran unasked, being the first call of a turn — a way to put a web
                // page over their notes; write_file asks before that and this did not.
                val saved = workspace.put(saveTo, page.text, replace = artifacts.isOwn(saveTo))
                if (saved.successful) {
                    artifacts.created(saveTo)
                    ToolExecution(
                        "Saved ${page.text.length} characters of $requested to " +
                            "$saveTo. It starts:" + "\n" + page.text.take(SAVED_PREVIEW),
                    )
                } else {
                    saved
                }
            } else {
                fetchedPageSuccess(page.text, requested, finalUrl.toString())
            }
        }
    }

    /**
     * The page, reduced to the parts that match what the model asked for.
     *
     * Carries the same [ToolEvidence.Fetch] a whole read does, because the model still
     * read that address and the same approval and provenance rules apply to what came
     * back. A search that found nothing is a successful read of a page that does not say
     * the thing, which is an answer; it is not a failed fetch.
     */
    private fun matchOutcome(
        text: String,
        find: String,
        requested: String,
        finalUrl: HttpUrl,
    ): ToolExecution = ToolExecution(
        text = PageSearch.render(PageSearch.search(text, find), find, text.length),
        evidence = ToolEvidence.Fetch(requested, finalUrl.toString()),
    )

    /**
     * What is worth reading in a response, or why nothing is.
     *
     * Both of these guards existed as constants and neither was applied, so the tool would
     * pull a response of any size and any type into memory and then try to read it as
     * prose. The model chooses this address, which is what makes it worth checking: a link
     * in a search result can point at a video, an archive, or a page that never stops.
     */
    private fun textOf(response: Response): PageText {
        if (!response.isSuccessful) return PageText("HTTP ${response.code}", successful = false)

        val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
        if (type != null && TEXTUAL.none { type.startsWith(it) }) {
            return PageText(
                "That address is $type, which is not text. Nothing to read.",
                successful = false,
            )
        }
        // peekBody stops at the limit rather than after it: the bytes past it are never
        // buffered, so a page with no end cannot exhaust the heap.
        return PageText(pageText(response.peekBody(MAX_BYTES.toLong()).string(), type))
    }

    /** One step of a fetch: either something to read, or somewhere else to look. */
    private sealed interface Hop {
        data class Read(val page: PageText) : Hop
        data class Moved(val to: HttpUrl) : Hop
    }

    /** Text from one HTTP response, before the page-level empty-content check. */
    private data class PageText(val text: String, val successful: Boolean = true)

    internal companion object {
        const val NAME = "fetch_url"

        /** Builds a successful page outcome without inferring success from its prose later. */
        fun fetchedPageSuccess(
            body: String,
            requestedUrl: String,
            finalUrl: String,
        ): ToolExecution = ToolExecution(
            text = body.take(MAX_CHARS) +
                "\n[truncated]".takeIf { body.length > MAX_CHARS }.orEmpty(),
            evidence = ToolEvidence.Fetch(requestedUrl, finalUrl),
        )

        /**
         * How many redirects one address may take before it is given up on.
         *
         * Five is what a browser allows for the same reason: a chain longer than that is a
         * loop or a tracker, and every hop is another address this tool has to be sure of.
         */
        const val SAVED_PREVIEW = 500

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

        /** Content types that are markup to be cleaned rather than text to be kept. */
        val HTML = listOf("text/html", "application/xhtml")

        /**
         * The body as the model should see it: cleaned when it is HTML, and otherwise as it
         * came.
         *
         * Every textual type used to go through the HTML cleaner, which cuts anything shaped
         * like a tag, decodes entities and folds newlines into spaces. That is right for a
         * page and wrong for everything else: a JSON body lost its `"a < b"` comparisons and
         * its line breaks, and with `save_to` the file a script then opened was not the
         * document the server sent. A type the server did not name is read as HTML, which
         * is what an unlabelled response almost always is.
         */
        fun pageText(body: String, contentType: String?): String =
            if (contentType == null || HTML.any { contentType.startsWith(it) }) {
                body.readable()
            } else {
                body
            }
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
 * Why a page will not be fetched at all, or null when it is worth dialling.
 *
 * A device transcript found the failure mode this exists for: LinkedIn answers HTTP 200 with
 * a genuine `<main>` landmark full of prose, so every check in [FetchUrlTool] waves it through,
 * and what is inside is the same "sign in to see the full profile" prompt repeated once per
 * gated section — About, Experience, contact info, connections. The model was handed
 * a fluent, wrong biography from that prompt, because nothing said the words it read were a
 * wall rather than a page. Distinguishing wall prose from article prose in general is not a
 * problem this tool can solve reliably: a heuristic tried against the actual response that
 * motivated this — repetition of the gating sentence — covered as little as 7% of the text,
 * because the sentence that repeats verbatim is short and the paragraphs around it are not,
 * so it is not a signal worth trusting site to site.
 *
 * What is reliable is that these specific sites are known, by name, to gate everything a
 * public request could ask for behind a sign-in wall, every time, regardless of the page.
 * Refusing before the request is also faster than the round trip that used to end in the same
 * refusal, and honest: the model is told the page requires an account rather than being handed
 * boilerplate that reads like an answer.
 */
internal fun walledGardenRefusal(url: HttpUrl): String? {
    val host = url.host.removePrefix("www.")
    if (host in PUBLIC_GARDEN_HOSTS) return null
    if (WALLED_GARDENS.none { host == it || host.endsWith(".$it") }) return null
    return "${url.host} requires signing in to show anything beyond its own login page, so " +
        "this could not be read. Answer from a search result instead, or say the page needs " +
        "an account rather than guessing its contents."
}

/**
 * Sites confirmed to gate their public pages behind a sign-in prompt rather than showing
 * anything a logged-out request asked for. Not a judgement about the site, only about whether
 * fetching it as this tool does — one request, no session, no script — can ever succeed.
 */
private val WALLED_GARDENS = setOf(
    "linkedin.com",
    "facebook.com",
    "instagram.com",
)

/**
 * The corners of those sites that are genuinely public: documentation and blogs served whole
 * to a logged-out request, on subdomains that never host the gated content. Named one by one
 * rather than by pattern, because the pattern is the problem — profiles live on arbitrary
 * country subdomains (`ph.linkedin.com/in/...` is the exact page this refusal was written
 * for), so "subdomains are fine" is wrong and "subdomains are walled" was refusing an API
 * reference that would have read perfectly well. A host in neither list falls through to the
 * refusal, which fails honest: the model says the page needs an account instead of reading it.
 */
private val PUBLIC_GARDEN_HOSTS = setOf(
    "engineering.linkedin.com",
    "developers.facebook.com",
    "about.instagram.com",
)

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
internal fun String.withoutFurniture(): String = mainContent().withoutElements(FURNITURE_TAGS)

/**
 * The text with every `<tag ...>...</tag>` whose name is in [names] cut out, in one pass.
 *
 * This was a regex, `<(script|style|...)[^>]*>.*?</\1>`, and it was quadratic on a page whose
 * furniture never closes: each unclosed `<script>` sent the lazy `.*?` to the end of the input
 * before giving up, so a 512 KB page of them cost three minutes of a laptop core, with no
 * suspension point for Stop to land on and the engine held for the whole of it. A page does
 * not have to be hostile to do this, only broken, and the cleaner runs on every page fetched.
 *
 * Scanning forward once keeps the same answer, an element removed from its open tag to the
 * first matching close, and bounds the work: when no close tag lies ahead of an open one,
 * that is remembered per name, so the text is searched to its end at most once per name.
 * An element that never closes is kept as it was, which is what the regex did too.
 *
 * Matched case-insensitively on the text itself rather than on a lowercased copy. The copy
 * was how this started, and its indices were used to slice the original, which is only
 * sound while the two are the same length. Kotlin's `lowercase()` does not promise that: a
 * capital I with a dot above becomes two characters, so a Turkish page with one before its
 * first `<script>` had every index past it off by one, and the slice either threw or kept
 * the script and cut the prose.
 */
private fun String.withoutElements(names: Set<String>): String {
    val out = StringBuilder(length)
    // Per name, the position from which a search for its close tag already came up empty.
    val noCloseFrom = HashMap<String, Int>()
    var from = 0
    var at = 0
    while (at < length) {
        val open = indexOf('<', at)
        if (open < 0) break
        val tag = tagNameAt(open + 1)?.takeIf { it in names }
        val openEnd = if (tag != null) indexOf('>', open) else -1
        val close = if (tag != null && openEnd >= 0) {
            closeTagAfter(tag, openEnd + 1, noCloseFrom)
        } else {
            -1
        }
        at = when {
            tag == null -> open + 1
            openEnd < 0 -> length // an open tag that never ends: the rest is kept as it is
            close < 0 -> openEnd + 1
            else -> {
                out.append(this, from, open).append(' ')
                from = close
                close
            }
        }
    }
    out.append(this, from, length)
    return out.toString()
}

/** The lowercase tag name starting at [start], or null when what follows is not a tag. */
private fun String.tagNameAt(start: Int): String? {
    var end = start
    while (end < length && (this[end] in 'a'..'z' || this[end] in 'A'..'Z')) end++
    if (end == start) return null
    // A name is whole only at a boundary: `<navigation>` is not `<nav>`.
    if (end < length && (this[end].isLetterOrDigit() || this[end] == '-')) return null
    // ASCII letters only, so lowercasing cannot change the length here.
    return substring(start, end).lowercase()
}

/**
 * The index just past the first `</name>` at or after [start], or -1 when there is none —
 * remembering in [noCloseFrom] that nothing lies ahead, so the next open tag of the same
 * name is answered without another search.
 */
private fun String.closeTagAfter(
    name: String,
    start: Int,
    noCloseFrom: MutableMap<String, Int>,
): Int {
    val known = noCloseFrom[name]
    if (known != null && known <= start) return -1
    var probe = start
    while (true) {
        val found = indexOf("</$name", probe, ignoreCase = true)
        if (found < 0) {
            noCloseFrom[name] = start
            return -1
        }
        var end = found + name.length + 2
        while (end < length && this[end].isWhitespace()) end++
        if (end < length && this[end] == '>') return end + 1
        probe = found + 1
    }
}

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
    val article = blocks("article").maxByOrNull { it.textLength() }
    if (article != null && article.textLength() >= ENOUGH_TO_BE_THE_BODY) return article
    return blocks("main").maxByOrNull { it.textLength() } ?: this
}

/**
 * Every outermost `<tag>...</tag>` in the document, counting depth rather than matching lazily.
 *
 * A regex cannot do this and the first version of this code tried. `<article>.*?</article>` is
 * left anchored and stops at the first close tag it meets, so an article holding a nested one,
 * which is legal HTML and is what a related-items card or a comment thread is, matched from the
 * outer open tag to the INNER close and returned a fragment. Where that fragment cleared the
 * threshold below, the body of the page was silently dropped and the model answered out of the
 * teaser. Reproduced before it was fixed: a six hundred character excerpt card in front of a
 * twelve hundred character body returned the card.
 *
 * Depth counting has the further advantage of failing safe. A document whose open tag is never
 * closed never returns to depth zero, emits nothing, and falls through to the fuller text.
 */
private fun String.blocks(tag: String): List<String> {
    val found = mutableListOf<String>()
    var depth = 0
    var start = -1
    for (mark in Regex("(?is)<(/?)$tag\\b[^>]*>").findAll(this)) {
        if (mark.groupValues[1].isEmpty()) {
            if (depth == 0) start = mark.range.first
            depth++
        } else if (depth > 0) {
            depth--
            if (depth == 0) {
                found += substring(start, mark.range.last + 1)
            }
        }
    }
    return found
}

/**
 * How much of a candidate is words rather than markup.
 *
 * The first version compared candidates by the length of their markup, which is the wrong
 * quantity by a wide margin: twenty cards of thumbnails and class lists outweigh a clean body,
 * so an index page beat an article. Tags are dropped rather than decoded, because decoding
 * entities is a platform call and this has to be answerable off a device.
 */
private fun String.textLength(): Int = replace(TAGS, " ").replace(RUNS_OF_SPACE, " ").trim().length

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
private val FURNITURE_TAGS = setOf(
    "script", "style", "nav", "header", "footer", "aside", "form", "noscript", "iframe",
    "svg", "template", "dialog",
)

/**
 * Below this much text an `<article>` is a teaser rather than a body, so the page is better
 * read whole.
 *
 * A card on an index page carries a headline and a sentence. A short real article still clears
 * this, and one that does not loses nothing: falling through returns the fuller text.
 */
private const val ENOUGH_TO_BE_THE_BODY = 500

private val TAGS = Regex("<[^>]+>")
private val RUNS_OF_SPACE = Regex("""\s+""")

private fun String.stripTags(): String = this
    .replace(TAGS, " ")
    .let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString() }
    .replace(RUNS_OF_SPACE, " ")
    .trim()

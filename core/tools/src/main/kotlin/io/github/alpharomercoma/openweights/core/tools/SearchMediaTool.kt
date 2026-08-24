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
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looking for pictures and clips rather than pages.
 *
 * A separate tool from `web_search` rather than an argument on it, which is the opposite of
 * what saves prompt tokens and is the right call anyway. A model given one search tool with
 * a `kind` argument reaches for the default and never passes the argument; measured on this
 * app's own tool suite, every optional argument that changes what a tool *does* rather than
 * how much it returns gets left out. Two names, two descriptions, and the model picks by
 * what it is looking for.
 *
 * ### What comes back, and to whom
 *
 * The model gets titles and page addresses, which is what it can reason about. The picture
 * addresses go in the same result because there is nowhere else for them to go: a tool
 * returns a string, so the interface reads them back out of it to draw the grid.
 *
 * That costs context, so the count is small and deliberate. Eight thumbnails is a grid
 * somebody can look at; it is also about four hundred tokens of URL, which is the real
 * reason not to return forty.
 */
@Singleton
class SearchMediaTool @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settings: SearchSettings,
    private val reachability: Reachability,
) : Tool {
    override val isAvailable: Boolean get() = reachability.isOnline()

    override val definition = ToolDefinition(
        name = NAME,
        description = "Find pictures or short videos on the web. Use it when the user asks " +
            "to see something rather than to read about it. Not for pages or facts.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "What to look for" },
                "kind": {
                  "type": "string",
                  "enum": ["images", "videos"],
                  "description": "Pictures or clips. Defaults to images."
                }
              },
              "required": ["query"]
            }
        """.trimIndent(),
    )

    /** It leaves the device, like every other search. */
    override val leavesTheDevice: Boolean = true
    override val returnsUntrustedText: Boolean = true

    override suspend fun run(call: ToolCall): String {
        val query = call.argument("query", "q", "search")
            ?: return "No query was given. Call $NAME again with what to look for."
        val kind = if (call.argument("kind", "type")?.startsWith("video") == true) {
            MediaResultKind.VIDEO
        } else {
            MediaResultKind.IMAGE
        }

        val provider = DuckDuckGoMediaProvider(settings.client(httpClient))
        val hits = provider.search(query, kind, LIMIT)
            ?: return "The search did not answer, which usually means it is rate limiting " +
                "rather than that there is nothing. Try again, or search the web instead."

        val what = if (kind == MediaResultKind.VIDEO) "clips" else "pictures"
        return buildString {
            append("Found ${hits.size} $what for \"$query\".\n")
            hits.forEachIndexed { index, hit ->
                append("\n${index + 1}. ${hit.title.ifBlank { "Untitled" }}")
                // Thumbnail and source on one line, in that order, because the interface
                // needs both: one to draw and one to open. They were separate lines and the
                // grid only parsed the first, so tapping a picture opened the picture rather
                // than the page it came from, which is not what its own comment claimed.
                if (hit.thumbnailUrl.isDrawable()) {
                    append(
                        "\n   $MEDIA ${hit.thumbnailUrl} ${hit.sourceUrl.ifBlank {
                            hit.thumbnailUrl
                        }}",
                    )
                }
            }
        }
    }

    companion object {
        const val NAME = "search_media"

        /**
         * The marker the interface reads thumbnails back out by.
         *
         * A prefix rather than JSON, because the same string is read by a person and by a
         * model, and a block of JSON in the middle of a tool result is noise to both.
         */
        const val MEDIA = "image:"

        /** Enough to look at, few enough that the URLs do not dominate the turn. */
        const val LIMIT = 8

        /**
         * Every picture in one tool result: what to draw, and what to open.
         *
         * Read back out of the tool's own text because a tool returns a string. Both halves
         * are needed and only one used to be parsed.
         */
        fun picturesIn(result: String): List<FoundPicture> = result.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(MEDIA) }
            .mapNotNull { line ->
                val parts = line.removePrefix(MEDIA).trim().split(' ').filter { it.isNotBlank() }
                val thumbnail = parts.firstOrNull() ?: return@mapNotNull null
                if (!thumbnail.isDrawable()) return@mapNotNull null
                FoundPicture(thumbnail = thumbnail, source = parts.getOrNull(1) ?: thumbnail)
            }
            .toList()
    }
}

/** One result, split into the address to draw and the address to open. */
data class FoundPicture(val thumbnail: String, val source: String)

/**
 * Whether an address is safe to hand to an image loader without asking anybody.
 *
 * A thumbnail is fetched the moment a reply renders, with no tap and no approval, which
 * makes it the one place in this app where a remote party chooses a URL the device will
 * request unprompted. A poisoned or proxy-modified search result carrying
 * `http://192.168.1.1/...` would have the app probe the user's own network as a side effect
 * of drawing a message.
 *
 * `https` only, so the address cannot be a plaintext probe, and a literal private address is
 * refused outright. That is not the same protection `fetch_url` gets, which resolves the
 * name through [PublicOnlyDns] and can therefore catch a public name pointing at a private
 * address; doing that here would mean resolving on the main thread before composing. The
 * scheme and literal checks are what is cheap and honest at this layer, and the remaining
 * gap is a name that resolves inward.
 */
internal fun String.isDrawable(): Boolean {
    if (!startsWith("https://")) return false
    val host = substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    if (host.isEmpty()) return false
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
        return false
    }
    // A literal address, which is the form an attack takes: a name would have to be
    // registered and would still be resolved by the loader.
    val literal = host.trim('[', ']')
    return runCatching {
        if (literal.none { it.isLetter() } || literal.contains(':')) {
            java.net.InetAddress.getByName(literal).isPublicAddress()
        } else {
            true
        }
    }.getOrDefault(false)
}

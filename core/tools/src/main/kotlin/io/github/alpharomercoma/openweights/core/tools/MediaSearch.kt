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

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** One picture or clip, with enough to show it and enough to say where it came from. */
data class MediaHit(
    val title: String,
    /** What to draw in the grid. Small on purpose: a results grid is thumbnails. */
    val thumbnailUrl: String,
    /** The full picture, or for a clip the page that plays it. */
    val targetUrl: String,
    /** The page it appears on, which is what attribution means here. */
    val sourceUrl: String,
    val kind: MediaResultKind,
    val width: Int = 0,
    val height: Int = 0,
)

enum class MediaResultKind { IMAGE, VIDEO }

/**
 * Pictures and clips from DuckDuckGo, which is the only one of the four that answers for
 * both without a key.
 *
 * Bing also serves images and is deliberately not used for them: its image endpoint wants
 * the same cookies as its web one and answers a phone with a challenge more often than not,
 * so a second scraper here would be a second thing to maintain that mostly returns nothing.
 *
 * ### The token that makes this work
 *
 * Neither endpoint answers without a `vqd`, a per-query token the search page embeds. It
 * cannot be guessed and it expires, so every search is two requests: fetch the page, read
 * the token, then ask. That is exactly what the site itself does, and it is why an image
 * search is slower than a web one.
 */
class DuckDuckGoMediaProvider(private val client: OkHttpClient) {
    suspend fun search(query: String, kind: MediaResultKind, limit: Int): List<MediaHit>? {
        val token = vqd(query)
        if (token == null) {
            // The one line that says which of the two requests died. "The search did not
            // answer" collapsed four different failures into one message, and three device
            // transcripts in a row could not say whether the token page or the endpoint was
            // the problem.
            Log.i(TAG, "media search: no vqd token came back from the search page")
            return null
        }
        val endpoint = when (kind) {
            MediaResultKind.IMAGE -> IMAGES
            MediaResultKind.VIDEO -> VIDEOS
        }
        val url = "$endpoint?l=us-en&o=json&q=${query.encoded()}&vqd=$token&p=-1"
        val body = runCatching {
            client.newCall(get(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.i(TAG, "media search: endpoint answered ${response.code}")
                    null
                } else {
                    response.peekBody(MAX_BYTES).string()
                }
            }
        }.onFailure {
            Log.i(TAG, "media search: endpoint request failed", it)
        }.getOrNull() ?: return null

        val results = runCatching {
            Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
        }.getOrNull()
        if (results == null) {
            Log.i(TAG, "media search: no results array in ${body.take(LOG_HEAD_CHARS)}")
            return null
        }

        // Null rather than empty, for the reason every provider here does it: an empty list
        // is a claim about the web, and a scraper that has been rate limited is not entitled
        // to make one.
        return results.mapNotNull { it.asHit(kind) }.take(limit).ifEmpty {
            Log.i(TAG, "media search: ${results.size} rows, none drawable")
            null
        }
    }

    /**
     * One row of the response, or null when it carries no picture to show.
     *
     * Every read is guarded rather than typed, because this is somebody else's undocumented
     * JSON: a field that is a string today is an object tomorrow, and one changed shape
     * should cost that row rather than the whole search.
     */
    private fun JsonElement.asHit(kind: MediaResultKind): MediaHit? {
        val row = runCatching { jsonObject }.getOrNull() ?: return null
        fun field(name: String) =
            runCatching { row[name]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        fun number(name: String) =
            runCatching { row[name]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull() ?: 0

        val thumbnail = when (kind) {
            MediaResultKind.IMAGE -> field("thumbnail").ifBlank { field("image") }
            // A video's thumbnail is nested, and the flat fields are all text.
            MediaResultKind.VIDEO -> runCatching {
                row["images"]?.jsonObject?.get("medium")?.jsonPrimitive?.content
            }.getOrNull().orEmpty()
        }
        if (thumbnail.isBlank()) return null

        val destination = when (kind) {
            MediaResultKind.IMAGE -> field("image")
            MediaResultKind.VIDEO -> field("content")
        }
        return MediaHit(
            title = field("title"),
            thumbnailUrl = thumbnail,
            targetUrl = destination,
            sourceUrl = when (kind) {
                MediaResultKind.IMAGE -> field("url")
                MediaResultKind.VIDEO -> destination
            },
            kind = kind,
            width = number("width"),
            height = number("height"),
        )
    }

    /** The per-query token the endpoints refuse to answer without. */
    private fun vqd(query: String): String? {
        val page = runCatching {
            client.newCall(get("$HOME/?q=${query.encoded()}").build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.i(TAG, "media search: token page answered ${response.code}")
                    null
                } else {
                    response.peekBody(MAX_BYTES).string()
                }
            }
        }.onFailure {
            Log.i(TAG, "media search: token page request failed", it)
        }.getOrNull() ?: return null
        val token = VQD.find(page)?.groupValues?.getOrNull(1)
        if (token == null) {
            // What came back instead matters: a consent page, a challenge, and an empty
            // shell all look identical from the null.
            val head = page.take(LOG_HEAD_CHARS).replace(Regex("\\s+"), " ")
            Log.i(TAG, "media search: ${page.length} chars of page, no vqd; starts $head")
        }
        return token
    }

    private fun get(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", SEARCH_USER_AGENT_BROWSER)
        .header("Referer", "$HOME/")

    private companion object {
        /** Enough of a page to say what it was, without logging the page. */
        const val LOG_HEAD_CHARS = 120

        const val TAG = "OpenWeights"
        const val HOME = "https://duckduckgo.com"
        const val IMAGES = "https://duckduckgo.com/i.js"
        const val VIDEOS = "https://duckduckgo.com/v.js"
        const val MAX_BYTES = 1L shl 20

        /**
         * Both spellings the page uses.
         *
         * It has been `vqd='...'` and `vqd="..."` and `vqd=4-123...` at different times, and
         * the token is worthless if this misses, so the pattern accepts all three rather
         * than tracking whichever is current.
         */
        val VQD = Regex("""vqd=["']?([\w-]+)["']?""")
    }
}

private fun String.encoded(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

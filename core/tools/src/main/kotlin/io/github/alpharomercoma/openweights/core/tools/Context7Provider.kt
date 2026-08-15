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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Library documentation, from Context7, with no key and no account.
 *
 * It sits in front of the general web rather than behind it, which is the opposite of how a
 * fallback chain usually grows and is the only arrangement that works. Asked about a library
 * it answers precisely, from an index built for exactly that. Asked anything else it still
 * answers: "what is the weather in Manila right now" comes back with thirty results, the
 * first three being Now in Android, a fetch wrapper called what-the-fetch, and a patch parser
 * called whatthepatch, all with scores in the same range as a real match. Both of those
 * responses are in the test resources, because that behaviour is the whole design problem and
 * a fixture is the only way to keep it in view.
 *
 * So the score cannot be the filter. What separates most of them is that a real match puts
 * the query's own words in the title, which [looksLikeAMatch] asks for and which throws out
 * every one of those three.
 *
 * It does not throw out all of it, and the remainder is the reason this source ships switched
 * off. The weather question also matches a library genuinely called Weather, which no filter
 * can refuse without refusing real matches too. Since the chain stops at the first source
 * that answers, being wrong here costs the web its turn, so the decision is the user's: see
 * [SearchSettings.searchesDocumentation].
 *
 * This makes the model's catalogue no larger. It is one more source behind `web_search`, not
 * a second tool to choose between, and choosing between tools is the thing that measurably
 * gets worse as the list grows.
 */
class Context7Provider(private val httpClient: OkHttpClient) : SearchProvider {
    override val id = "context7"
    override val label = "Context7"

    /** Nothing to configure: the search endpoint takes no key. */
    override val isConfigured = true

    override suspend fun search(query: String, limit: Int): List<SearchHit>? =
        withContext(Dispatchers.IO) {
            val url = SEARCH.asSearchUrl(query) ?: return@withContext null
            val body = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", SEARCH_USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body.string() else null
                }
            }.getOrNull() ?: return@withContext null

            parseContext7(body, query, limit)
        }

    private companion object {
        const val SEARCH = "https://context7.com/api/v1/search"
    }
}

/** Builds the query URL, keeping the escaping in one place. */
private fun String.asSearchUrl(query: String): HttpUrl? =
    toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("query", query)?.build()

/**
 * The hits worth showing, or null when the response could not be read.
 *
 * Internal so the two recorded responses can be run through it without a network.
 */
internal fun parseContext7(body: String, query: String, limit: Int): List<SearchHit>? {
    val results = runCatching {
        Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
    }.getOrNull() ?: return null

    return results.mapNotNull { entry ->
        val fields = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
        val title = fields.text("title") ?: return@mapNotNull null
        val id = fields.text("id") ?: return@mapNotNull null
        if (!looksLikeAMatch(query, title)) return@mapNotNull null
        SearchHit(
            title = title,
            snippet = fields.text("description").orEmpty(),
            url = "https://context7.com$id",
        )
    }.take(limit)
}

private fun kotlinx.serialization.json.JsonObject.text(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * Whether the title is about what was asked, rather than merely scoring well against it.
 *
 * Two clauses, and the second one was not there first. One long shared word looked like
 * enough until the recorded weather response went through it: "what is the weather in Manila
 * right now" and "PHP: The Right Way" share "right", which is five letters and means nothing.
 * A stoplist would have covered that one word and not the next.
 *
 * So either two of the query's words are in the title, which "Kotlinx Coroutines" manages for
 * "kotlin coroutines" and no accident does, or the title is entirely inside the query, which
 * is how "React" survives "react hooks" while being a single short word.
 */
internal fun looksLikeAMatch(query: String, title: String): Boolean {
    val asked = query.lowercase()
    val lowered = title.lowercase()
    val words = asked.split(NOT_A_WORD).filter { it.length >= MEANINGFUL_WORD }.distinct()
    if (words.count { lowered.contains(it) } >= AGREEING_WORDS) return true
    // The whole title, as words, being part of what was asked. "React" for "react hooks".
    // Every word of it has to carry weight as well, or a library called "Is" matches any
    // question with "is" in it, which is one that the recorded response actually contains.
    val titleWords = lowered.split(NOT_A_WORD).filter { it.isNotBlank() }
    return titleWords.isNotEmpty() &&
        titleWords.all { it.length >= MEANINGFUL_WORD && asked.contains(it) }
}

private val NOT_A_WORD = Regex("[^a-z0-9.+#]+")

/**
 * Five letters, which drops "what", "when", "with" and the rest by being no shorter.
 *
 * Chosen against the recorded responses rather than by taste: at three, "now" matches "Now in
 * Android" and a weather question comes back full of libraries.
 */
private const val MEANINGFUL_WORD = 5

/** Two, because one was measurably not enough. See [looksLikeAMatch]. */
private const val AGREEING_WORDS = 2

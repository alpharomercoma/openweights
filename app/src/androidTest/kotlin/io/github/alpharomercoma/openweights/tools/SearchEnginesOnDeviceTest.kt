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

package io.github.alpharomercoma.openweights.tools

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alpharomercoma.openweights.core.tools.SearchSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs every shipped search engine, and two candidates, on the device's own network.
 *
 * A measurement, not an assertion: which engines answer depends on the egress this phone
 * has, which is exactly why it cannot be settled from a laptop. The report goes to logcat
 * under `EngineProbe` as one JSON object per engine, recovered the same way the parity
 * suite's reports are. The only failure this test can have is not running.
 */
@RunWith(AndroidJUnit4::class)
class SearchEnginesOnDeviceTest {
    @Test
    fun probeEveryEngine() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = OkHttpClient()
        val providers = SearchSettings(context).providers(client)

        QUERIES.forEach { (query, expectedHost) ->
            providers.forEach { provider ->
                val started = System.currentTimeMillis()
                val hits = runCatching { provider.search(query, limit = 8) }.getOrNull()
                report(provider.id, query, expectedHost, started) {
                    hits.orEmpty().map { it.url }
                }
            }
            candidateYahoo(client, query, expectedHost)
            candidateMojeek(client, query, expectedHost)
        }
    }

    private inline fun report(
        engine: String,
        query: String,
        expectedHost: String?,
        started: Long,
        urls: () -> List<String>,
    ) {
        val found = runCatching(urls).getOrDefault(emptyList())
        val row = JSONObject()
            .put("engine", engine)
            .put("query", query)
            .put("hits", found.size)
            .put("relevant", expectedHost == null || found.take(5).any { expectedHost in it })
            .put("ms", System.currentTimeMillis() - started)
            .put("top", found.firstOrNull() ?: "")
        Log.i(TAG, row.toString())
    }

    /** The ddgs recipe: Bing's index through a door that actually answers phones. */
    private fun candidateYahoo(client: OkHttpClient, query: String, expectedHost: String?) {
        val started = System.currentTimeMillis()
        report("yahoo-candidate", query, expectedHost, started) {
            val url = "https://search.yahoo.com/search?p=" +
                java.net.URLEncoder.encode(query, "UTF-8")
            val body = fetch(client, url)
            Regex("""/RU=([^/"]+)/""").findAll(body)
                .map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
                .filter { it.startsWith("http") && "yahoo.com" !in it }
                .toList()
        }
    }

    private fun candidateMojeek(client: OkHttpClient, query: String, expectedHost: String?) {
        val started = System.currentTimeMillis()
        report("mojeek-candidate", query, expectedHost, started) {
            val url = "https://www.mojeek.com/search?q=" +
                java.net.URLEncoder.encode(query, "UTF-8")
            val body = fetch(client, url)
            Regex("""<h2><a[^>]+href="(https?://[^"]+)"""").findAll(body)
                .map { it.groupValues[1] }
                .toList()
        }
    }

    private fun fetch(client: OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html")
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.code != 200) "" else response.peekBody(1_000_000).string()
        }
    }

    /**
     * The media path, end to end: token page, JSON endpoint, and one thumbnail actually
     * fetched, so "the correct media is shown" is measured at the data it is drawn from.
     */
    @Test
    fun probeMediaSearch() = runBlocking {
        val client = OkHttpClient()
        val provider = io.github.alpharomercoma.openweights.core.tools
            .DuckDuckGoMediaProvider(client)
        listOf(
            io.github.alpharomercoma.openweights.core.tools.MediaResultKind.IMAGE,
            io.github.alpharomercoma.openweights.core.tools.MediaResultKind.VIDEO,
        ).forEach { kind ->
            val started = System.currentTimeMillis()
            val hits = runCatching {
                provider.search("golden retriever puppy", kind, limit = 6)
            }.getOrNull().orEmpty()
            val first = hits.firstOrNull()
            val thumbnailType = first?.let {
                runCatching {
                    client.newCall(
                        Request.Builder().url(it.thumbnailUrl)
                            .header("User-Agent", BROWSER_UA).build(),
                    ).execute().use { r -> r.header("Content-Type").orEmpty() }
                }.getOrDefault("unreachable")
            }.orEmpty()
            val row = JSONObject()
                .put("engine", "media-" + kind.name.lowercase())
                .put("hits", hits.size)
                .put(
                    "relevant",
                    hits.take(4).any {
                        "retriever" in it.title.lowercase() ||
                            "puppy" in it.title.lowercase() ||
                            "dog" in it.title.lowercase()
                    },
                )
                .put("thumb_type", thumbnailType)
                .put("ms", System.currentTimeMillis() - started)
                .put("top", first?.targetUrl ?: "")
            Log.i(TAG, row.toString())
        }
    }

    private companion object {
        const val TAG = "EngineProbe"
        const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        val QUERIES = listOf(
            "capital of mongolia" to "wikipedia.org",
            "llama.cpp github repository" to "github.com",
            "weather in manila today" to null,
        )
    }
}

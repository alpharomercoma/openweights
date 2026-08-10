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

package io.github.alpharomercoma.openweights.core.hub

import io.github.alpharomercoma.openweights.core.hub.HubHttp.withRange
import io.github.alpharomercoma.openweights.core.hub.HubHttp.withToken
import io.github.alpharomercoma.openweights.core.hub.gguf.ByteWindowSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a slice of a remote file with an HTTP range request.
 *
 * This is what makes inspecting a model cheap: the Hub serves ranges from its CDN, so the
 * app can read a GGUF header out of a two-gigabyte file without fetching the file.
 *
 * It lives beside the rest of the Hub client rather than in the app, because everything it
 * knows is the Hub's: its authentication, its status codes, and what its CDN does when it
 * decides to ignore a range.
 */
class RangeByteSource(
    private val httpClient: OkHttpClient,
    private val url: HttpUrl,
    private val tokenSource: HubTokenSource,
) : ByteWindowSource {

    override suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .withRange(offset, length)
            .withToken(tokenSource.token())
            .build()

        httpClient.newCall(request).execute().use { response ->
            when {
                response.code == HubHttp.PARTIAL_CONTENT -> response.body.bytes()

                // Reporting an empty read here would surface as "malformed GGUF", hiding a
                // token or rate-limit problem the user could actually fix.
                !response.isSuccessful ->
                    throw response.toHubException(hasToken = tokenSource.token() != null)

                // A 200 means the server ignored the range and is about to send the whole
                // file. Refusing beats streaming gigabytes into memory to read a header.
                else -> throw HubException(
                    "Hugging Face did not serve a byte range for this file, so it cannot " +
                        "be inspected before downloading.",
                )
            }
        }
    }

    @Singleton
    class Factory @Inject constructor(
        private val httpClient: OkHttpClient,
        private val tokenSource: HubTokenSource,
    ) {
        fun create(url: HttpUrl) = RangeByteSource(httpClient, url, tokenSource)
    }
}

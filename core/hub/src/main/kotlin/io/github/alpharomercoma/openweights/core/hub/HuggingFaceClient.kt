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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** A model repository as it appears in search results. */
data class HubModel(
    val id: String,
    val downloads: Int,
    val likes: Int,
    val isGated: Boolean,
    val tags: List<String>,
    val updatedAt: String?,
) {
    val owner: String get() = id.substringBefore('/', missingDelimiterValue = "")
    val name: String get() = id.substringAfter('/')
}

/** One downloadable GGUF inside a repository. */
data class HubFile(
    val path: String,
    val sizeBytes: Long,
    /** SHA-256 of the file contents, when the Hub reports one. Used to verify downloads. */
    val sha256: String?,
) {
    /**
     * The quantization, read from the filename.
     *
     * GGUF stores it as `general.file_type`, but llama.cpp writes that key *after* the
     * tokenizer, and reading past the tokenizer costs megabytes. Every GGUF published on
     * the Hub carries the quantization in its name, which is also where people look.
     */
    val quantizationLabel: String
        get() = path.substringAfterLast('/')
            .removeSuffix(".gguf")
            .substringAfterLast('-')
}

/** Everything the model detail screen needs about one repository. */
data class HubModelDetail(
    val model: HubModel,
    val files: List<HubFile>,
    val license: String?,
    val architecture: String?,
    val parameterCount: Long?,
    val trainingContextLength: Int?,
)

/** Raised when the Hub rejects a request in a way the user can act on. */
class HubException(message: String, val isAuthFailure: Boolean = false) : Exception(message)

/**
 * Reads the Hugging Face Hub.
 *
 * Deliberately read-only and scoped to model discovery: this app never uploads, and the
 * token it holds is only ever attached to requests to the Hub's own host.
 */
@Singleton
class HuggingFaceClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val tokenSource: HubTokenSource,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Searches models that ship GGUF files.
     *
     * The `gguf` library filter is what makes the result set runnable: without it the Hub
     * returns thousands of repositories this app could never load.
     */
    suspend fun search(
        query: String,
        sort: HubSort = HubSort.DOWNLOADS,
        limit: Int = DEFAULT_LIMIT,
    ): List<HubModel> {
        val url = apiUrl("models")
            .addQueryParameter("filter", "gguf")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", sort.parameter)
            .addQueryParameter("direction", "-1")
            .apply { if (query.isNotBlank()) addQueryParameter("search", query) }
            .build()

        return json.decodeFromString<List<SearchEntry>>(get(url)).map { it.toModel() }
    }

    /** Full detail for one repository, including its downloadable files. */
    suspend fun detail(repoId: String): HubModelDetail {
        val url = apiUrl("models", repoId)
            .addQueryParameter("blobs", "true")
            .build()
        val payload = json.decodeFromString<DetailEntry>(get(url))

        return HubModelDetail(
            model = payload.toModel(),
            files = payload.siblings.orEmpty()
                .filter { it.rfilename.endsWith(GGUF_SUFFIX, ignoreCase = true) }
                .map { sibling ->
                    HubFile(
                        path = sibling.rfilename,
                        sizeBytes = sibling.lfs?.size ?: sibling.size ?: 0L,
                        sha256 = sibling.lfs?.sha256,
                    )
                }
                .sortedBy { it.sizeBytes },
            license = payload.cardData?.license,
            architecture = payload.gguf?.architecture,
            parameterCount = payload.gguf?.total,
            trainingContextLength = payload.gguf?.contextLength,
        )
    }

    /** Confirms a token works, returning the account name it belongs to. */
    suspend fun whoAmI(): String {
        val payload = json.decodeFromString<WhoAmI>(get(apiUrl("whoami-v2").build()))
        return payload.name
    }

    /** Direct download URL for a file inside a repository. */
    fun downloadUrl(repoId: String, path: String): HttpUrl = HOST.newBuilder()
        .addPathSegments(repoId)
        .addPathSegment("resolve")
        .addPathSegment("main")
        .addPathSegments(path)
        .build()

    private fun apiUrl(vararg segments: String): HttpUrl.Builder =
        HOST.newBuilder().addPathSegment("api").apply {
            segments.forEach { addPathSegments(it) }
        }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val token = tokenSource.token()
        val request = Request.Builder()
            .url(url)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toHubException(hasToken = token != null)
            }
            response.body.string()
        }
    }

    private companion object {
        val HOST = "https://huggingface.co".toHttpUrl()
        const val GGUF_SUFFIX = ".gguf"
        const val DEFAULT_LIMIT = 30
    }
}

/** How search results are ordered. */
enum class HubSort(val parameter: String, val label: String) {
    DOWNLOADS("downloads", "Most downloaded"),
    LIKES("likes", "Most liked"),
    RECENT("lastModified", "Recently updated"),
}

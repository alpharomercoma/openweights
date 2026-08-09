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

import io.github.alpharomercoma.openweights.core.common.model.GgufFileName
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
        get() = fileName
            .removeSuffix(".gguf")
            .substringAfterLast('-')

    val fileName: String get() = path.substringAfterLast('/')

    /**
     * True for a multimodal projector rather than a chat model.
     *
     * Projectors are published in the same repository and end in `.gguf` like everything
     * else, but loading one as a model fails: it holds a vision or audio encoder and no
     * language model at all. Every publisher names them `mmproj-…`, which is the only
     * signal available without downloading the header.
     */
    val isProjector: Boolean get() = GgufFileName.isProjector(fileName)
}

/** Everything the model detail screen needs about one repository. */
data class HubModelDetail(
    val model: HubModel,
    /** Chat models only. Projectors are in [projectors]. */
    val files: List<HubFile>,
    /** Multimodal projectors offered by this repository, smallest first. */
    val projectors: List<HubFile>,
    val license: String?,
    val architecture: String?,
    val parameterCount: Long?,
    val trainingContextLength: Int?,
) {
    /**
     * The projector to download alongside [model].
     *
     * Matched to that specific file first, because a repository can hold several model
     * families and the wrong encoder loads without complaint and then describes pictures
     * it cannot see. Only when nothing matches by name does the single-projector case
     * apply, which is the common one, and unambiguous precisely because there is one.
     *
     * Among equals, the smallest: projectors are published at a couple of precisions, the
     * quality difference is slight, and the smaller file is both a shorter download and
     * less memory held for the whole session.
     */
    fun pairedProjector(model: HubFile): HubFile? {
        val identity = GgufFileName.modelIdentity(model.fileName)
        val matching = projectors.filter { projector ->
            val theirs = GgufFileName.modelIdentity(projector.fileName)
            identity.isNotEmpty() && theirs.equals(identity, ignoreCase = true)
        }
        val candidates = matching.ifEmpty { projectors.takeIf { it.size == 1 }.orEmpty() }
        return candidates.minByOrNull { it.sizeBytes }
    }

    /** True when this repository ships a vision or audio encoder. */
    val isMultimodal: Boolean get() = projectors.isNotEmpty()

    /** The projector for the file the user is most likely to take: the first listed. */
    fun defaultProjector(): HubFile? = files.firstOrNull()?.let(::pairedProjector)
}

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

        val gguf = payload.siblings.orEmpty()
            .filter { it.rfilename.endsWith(GGUF_SUFFIX, ignoreCase = true) }
            .map { sibling ->
                HubFile(
                    path = sibling.rfilename,
                    sizeBytes = sibling.lfs?.size ?: sibling.size ?: 0L,
                    sha256 = sibling.lfs?.sha256,
                )
            }
            .sortedBy { it.sizeBytes }

        return HubModelDetail(
            model = payload.toModel(),
            files = gguf.filterNot { it.isProjector },
            projectors = gguf.filter { it.isProjector },
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
/**
 * How search results are ordered.
 *
 * The labels are single words because they sit side by side in a row of chips: "Recently
 * updated" wrapped to a second line while its neighbours did not, which left the row
 * visibly ragged. A sort control does not need a sentence. The chips are read together,
 * and together they read as one choice.
 */
enum class HubSort(val parameter: String, val label: String) {
    DOWNLOADS("downloads", "Downloads"),
    LIKES("likes", "Likes"),
    RECENT("lastModified", "Recent"),
}

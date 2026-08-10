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
import io.github.alpharomercoma.openweights.core.common.model.GgufFileName.GGUF_SUFFIX
import io.github.alpharomercoma.openweights.core.hub.HubHttp.withToken
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
    /** The Hub's task tag, absent on a good number of repositories. */
    val pipelineTag: String? = null,
) {
    val owner: String get() = id.substringBefore('/', missingDelimiterValue = "")
    val name: String get() = id.substringAfter('/')

    /** True when the repository ships a vision encoder alongside the weights. */
    val isVision: Boolean
        get() = pipelineTag == HubTask.VISION.parameter ||
            pipelineTag == HubTask.ANY_TO_ANY.parameter

    /** True when it can be given a recording. */
    val isAudio: Boolean
        get() = pipelineTag == HubTask.AUDIO.parameter ||
            pipelineTag == HubTask.ANY_TO_ANY.parameter

    /**
     * The parameter count as the publisher wrote it in the name, for example `2.6B`.
     *
     * Read from the name rather than asked for, because the Hub only returns a repository's
     * true parameter count through `expand[]=gguf`, which also drags the whole chat
     * template along: 30 results grow from 15 KB to 270 KB. The name is what people read
     * anyway, and every quantizer puts the size in it.
     */
    val parameterHint: String?
        get() = PARAMETER_PATTERN.find(name)?.value?.uppercase()

    private companion object {
        /**
         * A number followed by B or M, on a boundary, as in `LFM2.5-2.6B-GGUF`.
         *
         * Case insensitive because `gemma-7b` and `Qwen3-8B` are both common. The
         * lookaround is what keeps it from reading a version number or a quantization
         * label as a size: a row that says 4B about a model of unknown size is worse than
         * a row that says nothing.
         */
        val PARAMETER_PATTERN = Regex(
            """(?<![A-Za-z0-9.])\d+(\.\d+)?[BM](?![A-Za-z0-9])""",
            RegexOption.IGNORE_CASE,
        )
    }
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
            .removeSuffix(GGUF_SUFFIX)
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
 * Read-only and scoped to model discovery: this app never uploads, and the
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
     * Searches models this app could actually run.
     *
     * Scoped by `apps=llama.cpp` rather than the `gguf` tag. The Hub computes that filter
     * from whether llama.cpp can load the repository, and the two differ in the results
     * that matter: the tag also matches Whisper GGUFs, video diffusion weights packaged as
     * GGUF, and control vectors, none of which are chat models, while the app filter
     * catches repositories that never got tagged. Sampling the top 500 by downloads,
     * roughly one in six differs between them.
     */
    suspend fun search(query: HubQuery, limit: Int = DEFAULT_LIMIT): List<HubModel> {
        val url = apiUrl("models")
            .addQueryParameter("apps", LLAMA_CPP)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", query.sort.parameter)
            .addQueryParameter("direction", "-1")
            .apply {
                query.text.trim().takeIf { it.isNotEmpty() }
                    ?.let { addQueryParameter("search", it) }
                query.task?.let { addQueryParameter("pipeline_tag", it.parameter) }
                query.author?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { addQueryParameter("author", it) }
                query.parameterBand?.let { addQueryParameter("num_parameters", it) }
                if (query.hideGated) addQueryParameter("gated", "false")
            }
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

    /**
     * The picture a publisher publishes under, if it has one.
     *
     * A repository id says who published it but not whether that is an organisation or a
     * person, and the two live at different paths. Organisations are tried first because
     * the labs people go looking for are organisations; a person costs one extra request,
     * and [PublisherAvatars] makes sure it is paid once.
     *
     * Returns null rather than throwing for every failure. A missing picture is not a
     * problem the user can do anything about, and a row draws perfectly well without one.
     */
    suspend fun avatarUrl(owner: String): String? {
        if (owner.isBlank()) return null
        return avatarUrlAt("organizations", owner) ?: avatarUrlAt("users", owner)
    }

    private suspend fun avatarUrlAt(kind: String, owner: String): String? = runCatching {
        val payload = get(apiUrl(kind, owner, "avatar").build())
        json.decodeFromString<AvatarEntry>(payload).avatarUrl
    }.getOrNull()

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
            .withToken(token)
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
        const val DEFAULT_LIMIT = 30

        /** The Hub's identifier for the local app this project is built on. */
        const val LLAMA_CPP = "llama.cpp"
    }
}

/** Everything the Discover screen can ask the Hub for. */
data class HubQuery(
    val text: String = "",
    val sort: HubSort = HubSort.TRENDING,
    /** The task the model is published for. Null means any. */
    val task: HubTask? = null,
    /** A single organisation or user, as it appears in the repository id. */
    val author: String? = null,
    val parameters: ParameterRange = ParameterRange.ANY,
    /**
     * A ceiling worked out from the device, in billions of parameters.
     *
     * Overrides [parameters] while it is set, because "what this phone can hold" and "8B
     * to 16B" are two answers to the same question and showing both would leave the user
     * to work out which one won.
     */
    val maxParametersBillions: Int? = null,
    val hideGated: Boolean = false,
) {
    /** How many filters are on, for the badge on the filter button. */
    val activeCount: Int = listOf(
        task != null,
        !author.isNullOrBlank(),
        parameters != ParameterRange.ANY,
        maxParametersBillions != null,
        hideGated,
    ).count { it }

    /** The `num_parameters` value for this query, or null when size is unconstrained. */
    internal val parameterBand: String?
        get() = maxParametersBillions?.let { "max:${it}B" } ?: parameters.parameter()
}

/**
 * How search results are ordered.
 *
 * The labels are single words because they sit side by side in a row of chips: "Recently
 * updated" wrapped to a second line while its neighbours did not, which left the row
 * visibly ragged. A sort control does not need a sentence. The chips are read together,
 * and together they read as one choice.
 */
enum class HubSort(val parameter: String, val label: String) {
    /** The Hub's own measure of what people are picking up right now. */
    TRENDING("trendingScore", "Trending"),
    DOWNLOADS("downloads", "Downloads"),
    LIKES("likes", "Likes"),
    RECENT("lastModified", "Recent"),
}

/**
 * The task a repository is published for.
 *
 * Only the tasks a chat app can use. The Hub's `apps=llama.cpp` set also contains
 * embedding, translation and even video models, which load and then cannot hold a
 * conversation, so being able to rule them out is worth a filter of its own. Left off by
 * default because roughly one repository in six carries no task tag at all, and filtering
 * would hide them.
 */
enum class HubTask(val parameter: String, val label: String, val description: String) {
    CHAT("text-generation", "Chat", "Text in, text out"),
    VISION("image-text-to-text", "Vision", "Reads pictures and video"),
    AUDIO("audio-text-to-text", "Audio", "Listens to recordings"),
    ANY_TO_ANY("any-to-any", "Any to any", "Several kinds of input at once"),
}

/**
 * A parameter count band.
 *
 * The Hub takes these as `min:`/`max:` with unit suffixes and applies them server side, so
 * a phone-sized band is one request rather than paging through 30B models to find the 3B
 * ones. The bands are chosen around what a phone can hold rather than around round
 * numbers: under 2B is comfortable everywhere, 8B is the edge of a 12 GB device.
 */
enum class ParameterRange(val label: String, val min: String?, val max: String?) {
    ANY("Any size", null, null),
    TINY("Under 2B", null, "2B"),
    SMALL("2B to 4B", "2B", "4B"),
    MEDIUM("4B to 8B", "4B", "8B"),
    LARGE("8B to 16B", "8B", "16B"),
    HUGE("Over 16B", "16B", null),
}

/** The `num_parameters` value for a band, or null when the band is everything. */
internal fun ParameterRange.parameter(): String? = listOfNotNull(
    min?.let { "min:$it" },
    max?.let { "max:$it" },
).joinToString(",").takeIf { it.isNotEmpty() }

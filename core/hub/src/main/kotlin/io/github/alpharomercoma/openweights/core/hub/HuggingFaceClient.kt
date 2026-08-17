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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /**
     * The recommended shortlist, fetched by name.
     *
     * Not a search. The Hub has no way to ask for "these five repositories", and a search
     * that happened to surface them would be a search that could stop surfacing them, so
     * each one is fetched by id and the failures are dropped. Concurrent, because it is a
     * handful of requests and they are the first screen anybody sees.
     */
    suspend fun recommended(): List<HubModel> = coroutineScope {
        RECOMMENDED
            .map { id -> async { runCatching { modelById(id) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun modelById(repoId: String): HubModel =
        json.decodeFromString<DetailEntry>(get(apiUrl("models", repoId).build())).toModel()

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
     * Who published this, as far as the Hub will say: their picture, and which kind of
     * account they are.
     *
     * A repository id says who published it but not whether that is an organisation or a
     * person, and the two live at different paths. Organisations are tried first because
     * the labs people go looking for are organisations; a person costs one extra request,
     * and [Publishers] makes sure it is paid once.
     *
     * Failures come back as an unknown publisher rather than as an exception. A missing
     * picture is not a problem the user can do anything about, and a row draws perfectly
     * well without one.
     */
    suspend fun publisher(owner: String): Publisher {
        if (owner.isBlank()) return Publisher()
        lookUp("organizations", owner)?.let {
            return Publisher(avatarUrl = it.avatarUrl, isOrganisation = true)
        }
        lookUp("users", owner)?.let {
            return Publisher(avatarUrl = it.avatarUrl, isOrganisation = false)
        }
        return Publisher()
    }

    /**
     * One lookup, distinguishing "answered without a picture" from "not this kind".
     *
     * It used to return the URL and nothing else, so an organisation that has never
     * uploaded a logo was indistinguishable from a name that is not an organisation at
     * all: both came back null and the caller fell through to the person endpoint. That
     * was harmless while the answer was only used to draw a circle. It is not harmless now
     * that it decides whether a model is shown.
     */
    private suspend fun lookUp(kind: String, owner: String): AvatarEntry? = runCatching {
        json.decodeFromString<AvatarEntry>(get(apiUrl(kind, owner, "avatar").build()))
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

/**
 * The models this app recommends, and why they are not the benchmark winners.
 *
 * `docs/research/tool-calling.md` ranks fifteen models on six routing cases in four
 * orderings plus a two-turn pair, all on hardware. Its winners are purpose-built function
 * callers: Hammer 2.1 1.5B at 5 of 6 in every arm, xLAM-2-1b at 5 of 6 and the fastest of
 * them. Neither is here, and the reason is worth writing down because it is the whole
 * lesson of that document read a second time.
 *
 * Hammer was installed on a Snapdragon 8 Gen 3 and asked who a particular person is. It
 * replied `[]`, which its template defines as "no tool applies", and the app rendered two
 * brackets in a code block. Asked again for an answer in words it produced sixty seconds of
 * `'t"' []` repeated until the phone reported itself hot. It routes better than anything
 * measured and it cannot hold a conversation, because routing is what it was fine-tuned to
 * do and the rest was trained out of it.
 *
 * So the shortlist selects on the axis a user actually meets first, which is whether the
 * thing answers, and treats routing as the second requirement rather than the first. That
 * axis is measured here on this hardware rather than taken from a card:
 *
 * - **LFM2 1.2B** and **LFM2.5 2.6B**, Liquid's own conversions. The 2.6B is the model
 *   this project has spent the most hours with, and the reason it leads is the behaviour
 *   the six routing cases do not score: asked about the World Cup it reasoned that "my
 *   knowledge might not be current", searched, and read a page, where the models that beat
 *   it on the benchmark answered from memory. It is the only thing measured here that
 *   notices an unknown. What it costs is time, up to seven and a half minutes on a turn
 *   that searches twice, which is why the 1.2B is beside it.
 * - **Qwen3 0.6B** answers coherently with visible reasoning at about 30 tokens a second
 *   in 610 MB, verified on device: "explain a KV cache", "17 times 23", both correct and
 *   both fast. It is also the one that invented a biography for a private individual
 *   rather than searching, so it is here as the fast generalist and not as the default.
 * - **Qwen3 1.7B** is the same family one size up, for a phone with room for it.
 *
 * What is still missing is a routing number for these two. Qwen 2.5 1.5B, the previous
 * generation, scores 4 of 6 with two under-calls, which is the failure a user reports as
 * "it answered from memory instead of searching". Until Qwen3 has been through
 * `ToolChoiceBenchmark` the list is honest about one axis and inferring the other, and
 * that is better than the reverse, which is what shipping Hammer would have been.
 */
val RECOMMENDED = listOf(
    "LiquidAI/LFM2-1.2B-GGUF",
    "LiquidAI/LFM2.5-2.6B-GGUF",
    "Qwen/Qwen3-0.6B-GGUF",
    "unsloth/Qwen3-1.7B-GGUF",
)

/**
 * What the Hub knows about whoever published a model.
 *
 * [isOrganisation] is the Hub's own distinction rather than a judgement of ours: Qwen,
 * Google, Meta and Unsloth are organisations; the accounts publishing abliterated merges
 * of their work are people. It is the closest thing the Hub has to "official", and it is
 * free, because the lookup that answers it is the one already made for the avatar.
 */
data class Publisher(val avatarUrl: String? = null, val isOrganisation: Boolean = false)

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
    /**
     * Only the models this app has actually been measured with.
     *
     * On by default, and it turns off every other filter while it is on: a shortlist of
     * five does not need narrowing by size or by publisher, and a chip row where three
     * things are lit and only one of them is doing anything is a row that has stopped
     * meaning anything.
     */
    val recommendedOnly: Boolean = true,
    /**
     * Only models published by an organisation, rather than by an individual account.
     *
     * Applied after the search rather than in it, because the Hub's model endpoint has no
     * parameter for it: what it has is two different paths for the two kinds of account,
     * which is the distinction itself. See `Publishers`.
     *
     * It was on by default, with the size ceiling, because the unfiltered Hub sorted by
     * trending is mostly abliterated merges of other people's work at sizes no phone can
     * hold. [recommendedOnly] now answers that better: a model published by an
     * organisation still routes tools badly if nobody has checked, and being checked is
     * what a first screen should be selecting for.
     */
    val officialOnly: Boolean = false,
) {
    /** How many filters are on, for the badge on the filter button. */
    val activeCount: Int = listOf(
        task != null,
        !author.isNullOrBlank(),
        parameters != ParameterRange.ANY,
        maxParametersBillions != null,
        hideGated,
        officialOnly,
        recommendedOnly,
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

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

import io.github.alpharomercoma.openweights.core.common.model.ExecuTorchFileName
import io.github.alpharomercoma.openweights.core.common.model.GgufFileName
import io.github.alpharomercoma.openweights.core.common.model.GgufFileName.GGUF_SUFFIX
import io.github.alpharomercoma.openweights.core.common.model.ModelFormat
import io.github.alpharomercoma.openweights.core.hub.HubHttp.withToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    /**
     * Which runtimes this repository has weights for.
     *
     * Filled in from the search that found it rather than from the repository listing: the
     * two runtimes are found through different Hub filters, so which search returned a
     * result is already the answer, and a repository returned by both has both. Reading it
     * any other way would cost a request per row.
     */
    val runtimes: Set<HubRuntime> = setOf(HubRuntime.LLAMA_CPP),
) {
    /** True when this repository ships weights compiled ahead of time for ExecuTorch. */
    val isCompiled: Boolean get() = HubRuntime.EXECUTORCH in runtimes
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
     * The last segment of the repository path, and never a way out of a directory.
     *
     * `substringAfterLast('/')` strips directories, which handles `../../x` by leaving `x`.
     * It does not handle a path whose last segment *is* `..`: that survives intact, and
     * `File(modelsDirectory, "..")` is the parent, so a repository could name a download
     * destination outside the folder meant to hold it. The blast radius is small, because
     * the parent is still this app's private storage, and the guard is one comparison, and
     * the whole point of this path is downloading a stranger's file.
     *
     * A backslash is refused for the same reason: it separates nothing on Android but does
     * on other systems, and a name is copied around.
     */
    val fileName: String
        get() = path.substringAfterLast('/').takeUnless { it.isUnsafeName() }.orEmpty()

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
    /**
     * Weights compiled ahead of time, for ExecuTorch. Empty for an ordinary GGUF repo.
     *
     * Almost always exactly one, and almost always called `model.pte`, which is why
     * [ExecuTorchFileName] renames it on the way in.
     */
    val compiled: List<HubFile> = emptyList(),
    /**
     * The tokenizers this repository publishes, which a `.pte` cannot be run without.
     *
     * A GGUF carries its tokenizer inside it. A `.pte` does not, and says nothing about
     * which one produced it, so a repository offering compiled weights and no tokenizer is
     * one this app cannot install. A list rather than one file, because a repository that
     * publishes several sizes of a family keeps a tokenizer beside each — see
     * [tokenizerFor] for how a weights file finds its own.
     */
    val tokenizers: List<HubFile> = emptyList(),
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

    /** The runtime this repository's weights need, or null when it offers neither. */
    val runtime: HubRuntime?
        get() = when {
            files.isNotEmpty() -> HubRuntime.LLAMA_CPP
            compiled.isNotEmpty() -> HubRuntime.EXECUTORCH
            else -> null
        }

    /**
     * True when the compiled weights here can actually be installed.
     *
     * A `.pte` without a tokenizer beside it is a download that ends in a model that cannot
     * open, so the offer is withheld rather than made and then broken.
     */
    val isInstallableCompiled: Boolean get() = compiled.isNotEmpty() && tokenizers.isNotEmpty()

    /**
     * The tokenizer that belongs to [weights]: the one in the nearest enclosing directory.
     *
     * A single-model repository keeps `tokenizer.json` at the root and that is the answer.
     * A multi-size repository keeps one beside each size (`1_2b/tokenizer.json` for
     * `1_2b/xnnpack/…pte`), and handing every size the root tokenizer would pair weights
     * with a vocabulary they were not exported against — the model would load and then
     * speak noise. Deepest matching directory wins; among equals, the JSON form.
     */
    fun tokenizerFor(weights: HubFile): HubFile? {
        val candidates = tokenizers.filter { tokenizer ->
            val directory = tokenizer.path.substringBeforeLast('/', "")
            directory.isEmpty() || weights.path.startsWith("$directory/")
        }
        // Scoped tokenizers existing anywhere in the repository means sizes have their
        // own vocabularies. A weights file in a folder with no tokenizer of its own must
        // then fail closed rather than borrow the root one: the borrow loads fine and
        // speaks noise (codex QA). The root file stays the answer only for weights at
        // the root, or when it is all the repository has.
        val scoped = tokenizers.any { '/' in it.path }
        val best = candidates.maxWithOrNull(
            compareBy(
                { it.path.count { character -> character == '/' } },
                { -ExecuTorchFileName.REMOTE_TOKENIZERS.indexOf(it.path.substringAfterLast('/')) },
            ),
        ) ?: return null
        val bestIsRoot = '/' !in best.path
        val weightsNested = '/' in weights.path
        return best.takeUnless { scoped && bestIsRoot && weightsNested }
    }

    /** The projector for the file the user is most likely to take: the first listed. */
    fun defaultProjector(): HubFile? = files.firstOrNull()?.let(::pairedProjector)
}

/**
 * Raised when the Hub rejects a request in a way the user can act on.
 *
 * @param isRetryable true when the same request stands a real chance of working shortly:
 * a rate limit, or the Hub itself being unwell. A download that gets one of these used to
 * fail at the first attempt and sit there wanting a tap, which is the opposite of what the
 * five-attempt backoff exists for, while a dropped socket (a strictly smaller problem)
 * got all five. Nothing about permission or a missing file is retryable: the answer is the
 * same however many times it is asked.
 */
class HubException(
    message: String,
    val isAuthFailure: Boolean = false,
    val isRetryable: Boolean = false,
) : Exception(message)

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
    suspend fun search(query: HubQuery, limit: Int = DEFAULT_LIMIT): List<HubModel> =
        searchPage(query, limit = limit).models

    /**
     * One page, and the cursor that reaches the next one.
     *
     * Cursor rather than offset, because `offset` is not a parameter this endpoint has. It
     * is accepted and ignored: measured against the live Hub, `offset=0`, `offset=3` and
     * `offset=6` all return the same three repositories. Paging on it appended a page the
     * screen already held, every model was dropped as a duplicate, and the list sat still
     * while asking for more forever. The Hub paginates with an opaque cursor handed back in
     * a `Link` header, which is what this follows.
     */
    /**
     * One page across every runtime the query asks for.
     *
     * The Hub has no single filter for "weights this phone can run": the two runtimes are
     * found through different parameters, so this is one request per runtime, run together
     * and merged. Compiled models come first, which is where the user is most likely to be
     * looking when they have asked for them at all and is also the smaller, curated set —
     * burying a handful of them under a thousand GGUFs would be the same as not having them.
     *
     * A repository returned by both searches appears once, carrying both runtimes, which is
     * what puts the ExecuTorch label on it.
     */
    suspend fun searchPage(
        query: HubQuery,
        cursor: String? = null,
        limit: Int = DEFAULT_LIMIT,
    ): HubSearchPage = coroutineScope {
        val wanted = query.runtimes.ifEmpty { HubRuntime.entries.toSet() }
        // A cursor names the runtime it came from, and a later page is asked of that
        // runtime alone. The same opaque cursor used to go to both searches, so the
        // compiled search received llama.cpp's cursor and answered nothing useful, and a
        // search of the compiled corner alone could never turn a page at all, since the
        // cursor handed back was always llama.cpp's and there was no llama.cpp page.
        val paging = cursor?.let(PagingCursor::decode)
        val pages = HubRuntime.entries
            .filter { it in wanted && (paging == null || it == paging.runtime) }
            .map { runtime ->
                async {
                    runtime to runCatching { runtimePage(query, runtime, paging?.cursor, limit) }
                }
            }
            .awaitAll()

        // One runtime failing must not empty the screen: the other half is still an answer.
        // Everything failing is a real failure and is raised, so the error banner still works.
        val good = pages.mapNotNull { (runtime, result) ->
            result.getOrNull()?.let { runtime to it }
        }
        if (good.isEmpty()) {
            throw pages.first().second.exceptionOrNull() ?: HubException("Search failed")
        }

        val merged = LinkedHashMap<String, HubModel>()
        good.forEach { (runtime, page) ->
            page.models.forEach { model ->
                val already = merged[model.id]
                merged[model.id] = (already ?: model).copy(
                    runtimes = (already?.runtimes ?: emptySet()) + runtime,
                )
            }
        }

        HubSearchPage(
            models = merged.values.sortedByDescending { it.isCompiled },
            // The llama.cpp half is the one worth paging when both were asked for: the
            // compiled corner of the Hub is small enough to arrive whole on the first page.
            // On its own, the compiled search pages on its own cursor.
            cursor = pagedHalf(good)?.let { (runtime, page) ->
                page.cursor?.let { PagingCursor(runtime, it).encode() }
            },
        )
    }

    private fun pagedHalf(good: List<Pair<HubRuntime, HubSearchPage>>) =
        good.firstOrNull { it.first == HubRuntime.LLAMA_CPP }
            ?: good.firstOrNull { it.first == HubRuntime.EXECUTORCH }

    private suspend fun runtimePage(
        query: HubQuery,
        runtime: HubRuntime,
        cursor: String?,
        limit: Int,
    ): HubSearchPage {
        val page = getPaged(searchUrl(query, runtime, cursor, limit))
        val models = json.decodeFromString<List<SearchEntry>>(page.body)
            .map { it.toModel().copy(runtimes = setOf(runtime)) }
            // The half of the size cap the Hub cannot apply. searchUrl leaves the band off
            // the compiled search because the Hub has nothing to count in a `.pte` repo,
            // so a 30B compiled model would sail past "under 10B" here. The name is what
            // there is: a hint over the ceiling is dropped, a repo with no hint is kept,
            // because hiding a model whose size is merely unstated is the very bug this
            // corner of the code just recovered from.
            .filter { runtime != HubRuntime.EXECUTORCH || it.withinCeiling(query) }
        return HubSearchPage(models = models, cursor = page.nextCursor)
    }

    /**
     * The Hub request for one runtime's page, visible so a test can hold it still.
     *
     * The size band is only sent on the llama.cpp half. `num_parameters` filters on
     * metadata the Hub reads out of safetensors files, and a compiled repository holds a
     * `.pte` and a tokenizer and nothing the Hub can count: measured live, `filter=
     * executorch&num_parameters=max:10B` returned 16 repositories, none of them the
     * executorch-community ones, while the same search without the band returned the whole
     * compiled corner. A size cap that silently removes every model it exists to surface
     * is worse than no cap, and the compiled corner is small enough not to need one.
     */
    internal fun searchUrl(
        query: HubQuery,
        runtime: HubRuntime,
        cursor: String? = null,
        limit: Int = DEFAULT_LIMIT,
    ): HttpUrl = apiUrl("models")
        .apply {
            when (runtime) {
                HubRuntime.LLAMA_CPP -> addQueryParameter("apps", LLAMA_CPP)
                HubRuntime.EXECUTORCH -> addQueryParameter("filter", EXECUTORCH)
            }
        }
        .addQueryParameter("limit", limit.toString())
        .apply { cursor?.let { addQueryParameter("cursor", it) } }
        .addQueryParameter("sort", query.sort.parameter)
        .addQueryParameter("direction", "-1")
        .apply {
            query.text.trim().takeIf { it.isNotEmpty() }
                ?.let { addQueryParameter("search", it) }
            query.task?.let { addQueryParameter("pipeline_tag", it.parameter) }
            query.author?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { addQueryParameter("author", it) }
            if (runtime == HubRuntime.LLAMA_CPP) {
                query.parameterBand?.let { addQueryParameter("num_parameters", it) }
            }
            if (query.hideGated) addQueryParameter("gated", "false")
        }
        .build()

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

        val siblings = payload.siblings.orEmpty()
        fun filesEnding(vararg suffixes: String) = siblings
            .filter { sibling -> suffixes.any { sibling.rfilename.endsWith(it, true) } }
            .map { sibling ->
                HubFile(
                    path = sibling.rfilename,
                    sizeBytes = sibling.lfs?.size ?: sibling.size ?: 0L,
                    sha256 = sibling.lfs?.sha256,
                )
            }

        // A repository is one or the other in practice, and asking for both costs nothing:
        // the answer is already in hand, and which one is populated is what says whether
        // this repo needs llama.cpp or ExecuTorch.
        val compiled = filesEnding(ModelFormat.PTE.suffix).sortedBy { it.sizeBytes }
        val tokenizers = siblings
            .filter { ExecuTorchFileName.isRemoteTokenizer(it.rfilename) }
            .map { HubFile(it.rfilename, it.lfs?.size ?: it.size ?: 0L, it.lfs?.sha256) }

        val gguf = filesEnding(GGUF_SUFFIX)
            .sortedBy { it.sizeBytes }
            // Bounded, because everything downstream is per file: the screen builds a row
            // for each, and the inspector launches a coroutine for each to read its header
            // over the network. A repository is somebody else's data, and one with a
            // thousand quantisations would turn opening it into a thousand range requests.
            // Sorted by size first, so the cut keeps the small ones, which are the ones a
            // phone can actually run.
            .take(MAX_FILES_PER_REPO)

        return HubModelDetail(
            model = payload.toModel(),
            files = gguf.filterNot { it.isProjector },
            projectors = gguf.filter { it.isProjector },
            compiled = compiled.take(MAX_FILES_PER_REPO),
            tokenizers = tokenizers,
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

    private suspend fun get(url: HttpUrl): String = getPaged(url).body

    /** One response body, with the cursor its `Link` header offers for the page after it. */
    private suspend fun getPaged(url: HttpUrl): Fetched = withContext(Dispatchers.IO) {
        val token = tokenSource.token()
        val request = Request.Builder()
            .url(url)
            .withToken(token)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toHubException(hasToken = token != null)
            }
            Fetched(response.body.string(), response.header("link").nextCursor())
        }
    }

    /** A body and nothing else, plus the one header that says there is more. */
    private data class Fetched(val body: String, val nextCursor: String?)

    /**
     * The cursor out of a `Link` header, taken apart rather than followed.
     *
     * Only the opaque cursor is kept and re-attached to an address this app builds itself,
     * so a header cannot redirect a search anywhere: the host is checked, and every other
     * parameter still comes from [HubQuery].
     */
    private fun String?.nextCursor(): String? {
        val next = this?.let { LINK_NEXT.find(it) }?.groupValues?.get(1)
        val address = next?.toHttpUrlOrNull()
        return address
            ?.takeIf { it.host == HOST.host }
            ?.queryParameter("cursor")
    }

    private companion object {
        val HOST = "https://huggingface.co".toHttpUrl()
        const val DEFAULT_LIMIT = 30

        /** `<address>; rel="next"`, which is how the Hub says there is another page. */
        val LINK_NEXT = Regex("""<([^>]+)>\s*;\s*rel="next"""")

        /** The Hub's identifier for the local app this project is built on. */
        const val LLAMA_CPP = "llama.cpp"

        /**
         * The tag ExecuTorch repositories carry, used through `filter` rather than `apps`.
         *
         * The Hub computes `apps` for a handful of tools and ExecuTorch is not one of them,
         * so the tag is what there is. `library=executorch` is not a substitute: the Hub
         * accepts it and returns ordinary results, so it fails by looking like it worked.
         */
        const val EXECUTORCH = "executorch"
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
 * - **LFM2.5 1.2B Instruct** leads, as the smallest thing here that still behaves like the
 *   2.6B rather than like a 0.6B. Same family, same conversion by the same publisher, and
 *   the size a phone loads without thinking about it.
 * - **LFM2.5 2.6B**, and the model this project has spent the most hours with. The reason it
 *   is here is the behaviour the six routing cases do not score:
 *   asked about the World Cup it reasoned that "my knowledge might not be current",
 *   searched, and read a page, where the models that beat it on the benchmark answered from
 *   memory. It is the only thing measured here that notices an unknown. What it costs is
 *   time, up to seven and a half minutes on a turn that searches twice.
 * - **LFM2.5 VL 3B** is the same family again with eyes, and the only entry here that can be
 *   handed a photograph. It ships its own `mmproj` projector, which the app pairs with the
 *   weights automatically, and it is the one model on the list that demonstrates the part of
 *   this app a text model cannot reach.
 * - **Qwen3 1.7B** is the generalist from another family, so the list is not one publisher's
 *   opinion of itself.
 *
 * **Two were dropped after use rather than after measurement, which is the point of a list
 * like this.** LFM2 1.2B and Qwen3 0.6B were here as the fast options and both are worse at
 * the thing the list is sorted by. The 0.6B invented a biography for a private individual
 * rather than searching, which is the exact failure the shortlist exists to keep off a first
 * run, and being quick about it is not a mitigation. A recommendation is a claim that this
 * is the one to start with, and a model that answers confidently and wrongly costs a new
 * user more than a slow one does.
 *
 * What is still missing is a routing number for these two. Qwen 2.5 1.5B, the previous
 * generation, scores 4 of 6 with two under-calls, which is the failure a user reports as
 * "it answered from memory instead of searching". Until Qwen3 has been through
 * `ToolChoiceBenchmark` the list is honest about one axis and inferring the other, and
 * that is better than the reverse, which is what shipping Hammer would have been.
 *
 * **The compiled entries joined on the backend-parity matrix** rather than on anybody's
 * card: `docs/research/backend-parity.md`, the same seven graded cases run greedy on both
 * engines, on this project's two phones (Dimensity 9400 and Snapdragon 8 Gen 3), grades
 * identical across the two SoCs.
 *
 * - **Qwen3 1.7B compiled** is the only model measured at 7 of 7 on both engines, tool
 *   loop included, so recommending the GGUF and not the `.pte` would be recommending the
 *   engine rather than the model.
 * - **LFM2.5 1.2B compiled** is the fastest decode ever measured in this project: 36.6
 *   tok/s on the Dimensity, 66.6 on the Snapdragon, against 21 to 24 for its own GGUF.
 *   One known divergence, a format-constraint case answered in prose, is the 8da4w
 *   export's quantisation rather than a harness fault, and it is written down.
 * - **SmolLM3 3B compiled** matched its GGUF case for case, including failing the same
 *   tool case for the same reason (a thinking budget spent before the call), which is
 *   what parity means. It is here as the compiled thinking model, from the runtime's own
 *   publisher.
 *
 * A build without the ExecuTorch runtime drops these three rows before they render — see
 * the Discover view model — so the standard build's shortlist is unchanged.
 */
val RECOMMENDED = listOf(
    "LiquidAI/LFM2.5-1.2B-Instruct-GGUF",
    "LiquidAI/LFM2.5-2.6B-GGUF",
    // Added on a routing number rather than on its parameter count, which is what the
    // paragraph above asks for and what the 8B is easiest to get wrong about.
    //
    // On a 142 case agentic suite, greedy, prompt rendered by the model's own template, it
    // scores 134/142 against the 2.6B's 127/142, and 138/142 once the tool prompt tells it
    // to answer from its own knowledge. It is the only model measured here that got every
    // multi-turn case right, 30 of 30, and it did not miss a single out-of-scope request.
    // Those are the two things a small model usually fails at, so the size is buying
    // exactly what it should.
    //
    // It is a mixture of experts, 32 of them with 4 used per token, so a phone pays roughly
    // a 1B model's arithmetic per token for an 8B model's judgement. What it does not
    // escape is memory: every expert has to be resident, and Q4_K_M is 4.8 GB, so this
    // belongs on a flagship and the fit card is what says so per device. There is no QAD
    // checkpoint at this size, unlike the 1.2B and 2.6B, so it cannot take the Q4_0 fast
    // path without the quantisation damage that made plain Q4_0 unusable elsewhere.
    //
    // The evidence is not one-sided and the list should say so. On a second suite of 48
    // cases built from this app's own eight tools it scored 38/48 against the 2.6B's 39,
    // and it reaches for web_search on general knowledge questions more often, six of
    // twelve against four.
    //
    // Separating the two rates changes the reading, though, and it is the reason this is
    // listed at all. Of the cases where a tool IS the right answer it calls one on 18 of
    // 18, the same as the 2.6B. Of the cases where no tool is right it calls one on 8 of
    // 30, against the 2.6B's 14. So it has the 2.6B's recall and half its false alarm
    // rate, which is a strictly better decision boundary rather than a quieter model:
    // d' of 2.95 against 2.41, and against 1.05 for the 1.2B, whose apparent restraint is
    // only a lower call rate and costs it a third of the calls it should make.
    //
    // The right pick for work that runs several steps deep. Its weakness is narrow and
    // known: it will look up a fact it already has.
    //
    // One trap if it is ever made the default. The tool descriptions in core:tools were
    // rewritten against the 2.6B and gain 5.6 points there, but they cost this model 7.8:
    // 135/142 against 127/142, with out-of-scope handling falling from 8/8 to 4/8. Wording
    // that helps a smaller model is not neutral on a larger one, so a change of default
    // model means re-running the suite rather than assuming the prompt work carries over.
    "LiquidAI/LFM2.5-8B-A1B-GGUF",
    "LiquidAI/LFM2.5-VL-3B-GGUF",
    "unsloth/Qwen3-1.7B-GGUF",
    // The compiled rows, in the order the matrix argues them: the 7/7 generalist, the
    // fastest decode measured on either phone, the thinking model at exact parity.
    "larryliu0820/Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK",
    "software-mansion/react-native-executorch-lfm-2.5",
    "pytorch/SmolLM3-3B-INT8-INT4",
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

/**
 * Which runtime a search is looking for models for.
 *
 * The Hub cannot answer "models this phone can run" — it answers "models packaged for this
 * tool", and the two runtimes are packaged, tagged and searched differently. A GGUF repo is
 * found through the `apps` filter the Hub computes for llama.cpp; an ExecuTorch repo is
 * found through its `executorch` tag. Measured against the live Hub, `library=executorch`
 * is accepted and silently ignored, returning ordinary GGUF repositories, which is exactly
 * the kind of filter that looks like it works.
 */
enum class HubRuntime(val format: ModelFormat) {
    /** Anything llama.cpp can read, which is most of the Hub. */
    LLAMA_CPP(ModelFormat.GGUF),

    /** Compiled ahead of time. A much smaller, curated corner of the Hub. */
    EXECUTORCH(ModelFormat.PTE),
}

/** One page of Hub results, and the way back for the next one. */
/**
 * The Hub's cursor, tagged with the runtime whose search produced it.
 *
 * What [HuggingFaceClient.searchPage] hands out and takes back. Opaque to the caller, which
 * only ever threads it through, and the tag is what lets the next page go to one search
 * rather than to both.
 */
internal data class PagingCursor(val runtime: HubRuntime, val cursor: String) {
    fun encode(): String = "${runtime.name}$SEPARATOR$cursor"

    companion object {
        private const val SEPARATOR = '|'

        /** A cursor with no tag is read as llama.cpp's, which is what every cursor once was. */
        fun decode(encoded: String): PagingCursor {
            val name = encoded.substringBefore(SEPARATOR, missingDelimiterValue = "")
            val runtime = HubRuntime.entries.firstOrNull { it.name == name }
                ?: return PagingCursor(HubRuntime.LLAMA_CPP, encoded)
            return PagingCursor(runtime, encoded.substringAfter(SEPARATOR))
        }
    }
}

data class HubSearchPage(
    val models: List<HubModel>,
    /** The Hub's opaque cursor for the page after this one, or null when there is none. */
    val cursor: String? = null,
) {
    val hasMore: Boolean get() = cursor != null
}

/** Everything the Discover screen can ask the Hub for. */
data class HubQuery(
    val text: String = "",
    /**
     * Which runtimes to find models for. Both, unless the user says otherwise.
     *
     * A set rather than a choice, because these are not alternatives to pick between: a
     * phone that can run both should be offered both, and the Hub is searched once per
     * runtime and the results merged. Deliberately not counted in [activeCount] — it
     * changes which corners of the Hub are searched rather than narrowing a result set.
     *
     * Empty is treated as "all of them" rather than "none", so unticking every box shows
     * everything instead of an empty screen the user has to undo.
     */
    val runtimes: Set<HubRuntime> = HubRuntime.entries.toSet(),
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

/** Whether the size the publisher wrote in the name fits the query's ceiling, if both exist. */
private fun HubModel.withinCeiling(query: HubQuery): Boolean {
    val ceiling = query.maxParametersBillions ?: return true
    val hint = parameterHint ?: return true
    val number = hint.dropLast(1).toDoubleOrNull() ?: return true
    val millions = hint.endsWith("M", ignoreCase = true)
    val billions = if (millions) number / MILLIONS_PER_BILLION else number
    return billions <= ceiling
}

private const val MILLIONS_PER_BILLION = 1000

/** The `num_parameters` value for a band, or null when the band is everything. */
internal fun ParameterRange.parameter(): String? = listOfNotNull(
    min?.let { "min:$it" },
    max?.let { "max:$it" },
).joinToString(",").takeIf { it.isNotEmpty() }

/**
 * Whether a name would escape the directory it is meant to land in, or is not a name at all.
 *
 * Deliberately a denylist of the three shapes that are not names, rather than an allowlist
 * of permitted characters: model files legitimately carry dots, dashes, plus signs and
 * non-Latin scripts, and an allowlist would reject real files to guard against a case that
 * a denylist covers exactly.
 */
private fun String.isUnsafeName(): Boolean = isBlank() ||
    this == "." ||
    this == ".." ||
    contains('/') ||
    contains('\\') ||
    contains('\u0000')

private const val MAX_FILES_PER_REPO = 40

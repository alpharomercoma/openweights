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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Response

/*
 * Wire shapes for the Hub's model API. These stay internal: the rest of the app talks in
 * HubModel / HubFile, so a change to the Hub's JSON is contained to this file.
 */

@Serializable
internal data class SearchEntry(
    val id: String,
    val downloads: Int = 0,
    val likes: Int = 0,
    val gated: JsonElement? = null,
    val tags: List<String> = emptyList(),
    val lastModified: String? = null,
    @SerialName("pipeline_tag") val pipelineTag: String? = null,
) {
    fun toModel() = HubModel(
        id = id,
        downloads = downloads,
        likes = likes,
        isGated = gated.isGated(),
        tags = tags,
        updatedAt = lastModified,
        pipelineTag = pipelineTag,
        // A search result's runtime is overwritten by which search returned it; a repo
        // fetched by id has no search to say so, and the tag is the same signal the
        // ExecuTorch search itself filters on. Without this, a compiled repository on the
        // recommended shortlist would wear a GGUF label and be handed to the wrong engine.
        runtimes = if (EXECUTORCH_TAG in tags) {
            setOf(HubRuntime.EXECUTORCH)
        } else {
            setOf(HubRuntime.LLAMA_CPP)
        },
    )
}

/**
 * Top level rather than a companion, and that is load-bearing: declaring any companion on
 * a @Serializable class is where the serialization plugin then puts `serializer()`, and a
 * *private* companion made every generated serializer access in this module throw
 * IllegalAccessError at runtime while compiling clean.
 */
private const val EXECUTORCH_TAG = "executorch"

@Serializable
internal data class DetailEntry(
    val id: String,
    val downloads: Int = 0,
    val likes: Int = 0,
    val gated: JsonElement? = null,
    val tags: List<String> = emptyList(),
    val lastModified: String? = null,
    val siblings: List<Sibling>? = null,
    val cardData: CardData? = null,
    val gguf: GgufSummary? = null,
    @SerialName("pipeline_tag") val pipelineTag: String? = null,
) {
    fun toModel() = HubModel(
        id = id,
        downloads = downloads,
        likes = likes,
        isGated = gated.isGated(),
        tags = tags,
        updatedAt = lastModified,
        pipelineTag = pipelineTag,
    )
}

/** The one field of a publisher's profile this app has any use for. */
@Serializable
internal data class AvatarEntry(val avatarUrl: String? = null)

@Serializable
internal data class Sibling(val rfilename: String, val size: Long? = null, val lfs: Lfs? = null)

@Serializable
internal data class Lfs(val size: Long? = null, @SerialName("sha256") val sha256: String? = null)

@Serializable
internal data class CardData(val license: String? = null)

/** Repo-level summary the Hub derives from the GGUF files it hosts. */
@Serializable
internal data class GgufSummary(
    val total: Long? = null,
    val architecture: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
)

@Serializable
internal data class WhoAmI(val name: String)

/**
 * `gated` is `false` for open repos but a string such as `"auto"` or `"manual"` for gated
 * ones, so it cannot be decoded as a plain boolean.
 */
private fun JsonElement?.isGated(): Boolean {
    val primitive = this as? JsonPrimitive ?: return false
    return primitive.content !in setOf("false", "null")
}

/** Turns an HTTP failure into something the UI can tell the user to do something about. */
fun Response.toHubException(hasToken: Boolean): HubException = when (code) {
    HTTP_UNAUTHORIZED -> HubException(
        if (hasToken) {
            "Hugging Face rejected your access token. Check it in Settings."
        } else {
            "This model needs a Hugging Face access token. Add one in Settings."
        },
        isAuthFailure = true,
    )

    HTTP_FORBIDDEN -> HubException(
        "Your account does not have access to this model. Accept its terms on " +
            "huggingface.co, then try again.",
        isAuthFailure = true,
    )

    HTTP_NOT_FOUND -> HubException("That model no longer exists on Hugging Face.")
    HTTP_TOO_MANY_REQUESTS -> HubException(
        "Hugging Face is rate limiting requests. Try again shortly.",
        isRetryable = true,
    )

    in HTTP_SERVER_ERRORS -> HubException(
        "Hugging Face is having trouble right now ($code). Trying again shortly.",
        isRetryable = true,
    )

    else -> HubException("Hugging Face returned $code.")
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429

/**
 * The Hub's own failures, which are the Hub's to recover from and worth waiting out.
 *
 * A download resumes from the bytes already on disk, so a wait costs nothing but time,
 * and 500, 502 and 503 from a CDN are almost always over by the second attempt.
 */
private val HTTP_SERVER_ERRORS = 500..599

/** True when a partial response says, in its `Content-Range`, that it starts at [offset]. */
fun Response.servesRangeFrom(offset: Long): Boolean {
    val header = header("Content-Range") ?: return false
    return header.substringAfter("bytes ", "").substringBefore('-') == offset.toString()
}

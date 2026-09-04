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

package io.github.alpharomercoma.openweights.core.data

import io.github.alpharomercoma.openweights.core.common.model.ReplyConfidence
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One reply's per-token confidences, as stored in the messages table.
 *
 * Two parallel arrays rather than an array of objects, because this is the largest thing
 * that will ever be written to a message row and the difference is not small: `{"t":"the",
 * "p":-0.01}` per token is roughly twice `"the"` and `-0.01` in two lists. A three hundred
 * token answer is about four kilobytes this way, and it is only written at all for someone
 * who has turned the uncertainty view on.
 *
 * Per token rather than the runs the screen actually draws, deliberately. Runs are merged
 * at a threshold, and the threshold is a constant this app has not yet calibrated on a
 * phone; storing the merged form would bake today's guess into every row and make an old
 * reply unreadable at tomorrow's threshold. Tokens are the measurement, runs are a view of
 * it, and only the measurement is worth keeping.
 */
@Serializable
private data class StoredConfidence(
    /** Each token's text, in order. Concatenated, they are the answer that was measured. */
    val t: List<String>,
    /** The natural log of the probability the model gave each of them. */
    val p: List<Float>,
)

private val json = Json { ignoreUnknownKeys = true }

/** Encodes a reply's token confidences. Null when there are none, which keeps rows small. */
internal fun encodeConfidence(texts: List<String>, logprobs: List<Float>): String? =
    if (texts.isEmpty() || texts.size != logprobs.size) {
        null
    } else {
        json.encodeToString(StoredConfidence(texts, logprobs))
    }

/**
 * Reads a reply's confidences back, folded into runs at the current threshold.
 *
 * Returns [ReplyConfidence.NONE] for anything unreadable, on the same reasoning as the
 * attachments beside it: a row written by a future build must not stop an old one opening
 * the conversation. Losing an underline is recoverable; losing the conversation is not.
 */
fun String?.decodeConfidence(answer: String? = null): ReplyConfidence {
    if (isNullOrEmpty()) return ReplyConfidence.NONE
    return runCatching {
        val stored = json.decodeFromString<StoredConfidence>(this)
        ReplyConfidence.of(stored.t, stored.p, answer)
    }.getOrDefault(ReplyConfidence.NONE)
}

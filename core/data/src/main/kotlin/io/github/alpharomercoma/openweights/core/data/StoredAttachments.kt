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

import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An attachment as stored in the messages table.
 *
 * A separate type from [MessagePart.File] so the on-disk shape can stay put while the
 * in-memory one changes: this JSON is written into a database that survives upgrades.
 */
@Serializable
private data class StoredAttachment(
    val path: String,
    val mediaType: String,
    val name: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/** Encodes attachments for storage. Null when there are none, which keeps rows small. */
internal fun List<MessagePart.File>.encodeAttachments(): String? = if (isEmpty()) {
    null
} else {
    json.encodeToString(map { StoredAttachment(it.path, it.mediaType, it.name) })
}

/**
 * Reads attachments back.
 *
 * Returns an empty list for anything unreadable. A row written by a future version must
 * not stop an old build opening the conversation — losing a thumbnail is recoverable,
 * losing the conversation is not.
 */
fun String?.decodeAttachments(): List<MessagePart.File> {
    if (isNullOrEmpty()) return emptyList()
    return runCatching {
        json.decodeFromString<List<StoredAttachment>>(this)
            .map { MessagePart.File(it.path, it.mediaType, it.name) }
    }.getOrDefault(emptyList())
}

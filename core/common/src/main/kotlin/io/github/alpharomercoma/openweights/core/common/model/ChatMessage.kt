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

package io.github.alpharomercoma.openweights.core.common.model

/** Who authored a message. The wire values match what chat templates expect. */
enum class ChatRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

/**
 * A piece of a message.
 *
 * Modelled on the Vercel AI SDK's message parts: text and files, where a file carries its
 * IANA media type rather than being split into separate image and audio types. That shape
 * has become the de facto interchange format for multimodal chat, and it is also the
 * honest one here — llama.cpp's projector inspects the bytes and decides what it can do
 * with them, so an image part is a claim about the file, not about the model.
 */
sealed interface MessagePart {
    data class Text(val text: String) : MessagePart

    /**
     * A local file attached to the message.
     *
     * The AI SDK's equivalent part carries either a URL or base64 data; this one carries a
     * filesystem path instead, because the projector reads the file directly and the app
     * copies every attachment into its own storage before sending. That copy is also what
     * keeps an attachment viewable months later, after the URI it was picked from has been
     * revoked or the original deleted.
     *
     * @param path an absolute path to a readable file.
     * @param mediaType an IANA media type, for example `image/jpeg` or `audio/wav`.
     * @param name what to show the user. Falls back to the media type when absent.
     */
    data class File(val path: String, val mediaType: String, val name: String? = null) :
        MessagePart {
        val kind: MediaKind get() = MediaKind.of(mediaType)
    }
}

/**
 * The broad category of an attachment.
 *
 * Derived from the media type rather than stored, so a file picked from any source
 * classifies the same way. Used to choose an icon, decide whether a model can accept the
 * file at all, and warn before a large video is sent to a model that cannot read it.
 */
enum class MediaKind {
    IMAGE,
    AUDIO,
    VIDEO,
    OTHER,
    ;

    companion object {
        fun of(mediaType: String): MediaKind = when (mediaType.substringBefore('/').lowercase()) {
            "image" -> IMAGE
            "audio" -> AUDIO
            "video" -> VIDEO
            else -> OTHER
        }
    }
}

/** One turn of a conversation as handed to the inference engine. */
data class ChatMessage(val role: ChatRole, val parts: List<MessagePart>) {
    /** The text of this message with attachments omitted. */
    val text: String get() = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }

    /** Attachments in the order they appear in the message. */
    val files: List<MessagePart.File> get() = parts.filterIsInstance<MessagePart.File>()

    /**
     * The message as the chat template should see it, with [marker] standing in for each
     * attachment.
     *
     * The projector replaces each marker with the embeddings for the corresponding file, so
     * position matters: the marker has to sit where the attachment was in the message, and
     * the count has to match exactly.
     */
    fun withMediaMarkers(marker: String): String = buildString {
        parts.forEach { part ->
            when (part) {
                is MessagePart.Text -> append(part.text)
                is MessagePart.File -> {
                    if (isNotEmpty() && !endsWith('\n')) append('\n')
                    append(marker)
                    append('\n')
                }
            }
        }
    }

    companion object {
        fun text(role: ChatRole, text: String): ChatMessage =
            ChatMessage(role, listOf(MessagePart.Text(text)))
    }
}

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
 * A piece of a message. Text is always supported; media parts require a model with a
 * matching multimodal projector.
 */
sealed interface MessagePart {
    data class Text(val text: String) : MessagePart

    /** A local image file to be encoded by the model's vision projector. */
    data class Image(val uri: String) : MessagePart

    /** A local audio file to be encoded by the model's audio projector. */
    data class Audio(val uri: String) : MessagePart
}

/** One turn of a conversation as handed to the inference engine. */
data class ChatMessage(val role: ChatRole, val parts: List<MessagePart>) {
    /** The text of this message with media parts omitted. */
    val text: String get() = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }

    companion object {
        fun text(role: ChatRole, text: String): ChatMessage =
            ChatMessage(role, listOf(MessagePart.Text(text)))
    }
}

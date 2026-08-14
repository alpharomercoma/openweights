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

package io.github.alpharomercoma.openweights.ui.chat

import android.net.Uri
import io.github.alpharomercoma.openweights.core.common.model.MediaKind
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.engine.MediaSupport
import io.github.alpharomercoma.openweights.model.AttachmentStore
import io.github.alpharomercoma.openweights.model.StagedDocument
import javax.inject.Inject

/**
 * What came of trying to attach a file: something to send, or a reason it cannot be.
 *
 * A result rather than a thrown exception, because every one of these is a sentence for the
 * user rather than a failure: the file would not open, or it opened and this model has no
 * way to read it. Both are ordinary answers to picking the wrong file.
 */
sealed interface Staged {
    data class Files(val files: List<MessagePart.File>) : Staged

    data class Document(val document: StagedDocument) : Staged

    data class Refused(val why: String) : Staged
}

/**
 * Turns a file the user picked into something the next message can carry.
 *
 * Its own object because deciding whether a file can be sent is a separate question from
 * running a turn, and it is the one part of the chat screen with real rules in it: whether
 * this model can read the kind of file at all, how much of a document fits in what is left
 * of the window, and what to say when the answer is no. The view model keeps ownership of
 * the screen and applies whatever this returns.
 */
class Staging @Inject constructor(private val attachments: AttachmentStore) {
    /**
     * Copies a picked file in and says whether this model can read it.
     *
     * Checked here rather than at send: the engine drops media a model has no projector
     * for, so an unsupported file would otherwise sit in the composer, appear in the sent
     * message, be stored with it, and never reach the model.
     */
    suspend fun file(uri: Uri, support: MediaSupport): Staged {
        val stored = attachments.store(uri)
        if (stored.isEmpty()) return Staged.Refused("That file could not be read.")

        val unreadable = stored.filterNot { support.accepts(it.kind) }
        if (unreadable.isNotEmpty()) {
            attachments.discard(stored)
            return Staged.Refused(support.rejection(unreadable.first()))
        }
        return Staged.Files(stored)
    }

    /**
     * Reads as much of a text document as the window has room for.
     *
     * Offered whatever model is loaded, which is the point of it: reading a document takes
     * no projector and no vision, so this is the one attachment a plain text model can use.
     * How much fits is decided from the window the loaded model actually has, because a
     * document that overruns the context does not produce a worse answer, it produces a
     * failed decode.
     */
    suspend fun document(uri: Uri, budgetChars: Int): Staged =
        attachments.readDocument(uri, budgetChars)
            ?.let(Staged::Document)
            ?: Staged.Refused("That file could not be read as text.")

    suspend fun discard(attachment: MessagePart.File) = attachments.discard(attachment)

    suspend fun discard(attachments: List<MessagePart.File>) = this.attachments.discard(attachments)
}

/**
 * Why a picked file cannot be sent to the loaded model.
 *
 * Names what the model can read rather than only what it cannot, so the next attempt is an
 * informed one.
 */
internal fun MediaSupport.rejection(rejected: MessagePart.File): String {
    val readable = listOfNotNull(
        "pictures".takeIf { vision },
        "sound".takeIf { audio },
    )
    val what = when (rejected.kind) {
        MediaKind.IMAGE -> "pictures"
        MediaKind.AUDIO -> "sound"
        MediaKind.VIDEO -> "video"
        MediaKind.OTHER -> "files of this type"
    }
    return if (readable.isEmpty()) {
        "This model reads text only. Load a model with a projector file to send $what."
    } else {
        "This model cannot read $what. It reads ${readable.joinToString(" and ")}."
    }
}

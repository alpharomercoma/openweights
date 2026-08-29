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

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.model.MediaKind
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.engine.MediaSupport
import io.github.alpharomercoma.openweights.model.AttachmentResult
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
class Staging @Inject constructor(
    private val attachments: AttachmentStore,
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Copies a picked file in and says whether this model can read it.
     *
     * Checked here rather than at send: the engine drops media a model has no projector
     * for, so an unsupported file would otherwise sit in the composer, appear in the sent
     * message, be stored with it, and never reach the model.
     */
    suspend fun file(uri: Uri, support: MediaSupport): Staged {
        val stored = when (val result = attachments.store(uri)) {
            is AttachmentResult.Stored -> result.files
            is AttachmentResult.TooLarge -> return Staged.Refused(
                context.getString(
                    R.string.attachment_too_large,
                    result.limitBytes / BYTES_PER_MEBIBYTE,
                ),
            )
            AttachmentResult.Unreadable ->
                return Staged.Refused(context.getString(R.string.attachment_unreadable))
        }

        val unreadable = stored.filterNot { support.accepts(it.kind) }
        if (unreadable.isNotEmpty()) {
            attachments.discard(stored)
            return Staged.Refused(support.rejection(unreadable.first(), context))
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
            ?: Staged.Refused(context.getString(R.string.document_unreadable))

    /**
     * Why these files cannot be sent to this model, or null when they can.
     *
     * The same question [file] answers on the way in, asked again by [Attaching] once the
     * copy is finished, because the model can be changed while a large file is still moving.
     */
    fun unreadable(files: List<MessagePart.File>, support: MediaSupport): String? =
        files.firstOrNull { !support.accepts(it.kind) }?.let { support.rejection(it, context) }

    suspend fun discard(attachment: MessagePart.File) = attachments.discard(attachment)

    suspend fun discard(attachments: List<MessagePart.File>) = this.attachments.discard(attachments)

    suspend fun duplicate(attachments: List<MessagePart.File>): List<MessagePart.File> =
        this.attachments.duplicate(attachments)
}

private const val BYTES_PER_MEBIBYTE = 1024L * 1024L

/**
 * Why a picked file cannot be sent to the loaded model.
 *
 * Names what the model can read rather than only what it cannot, so the next attempt is an
 * informed one.
 */
internal fun MediaSupport.rejection(rejected: MessagePart.File, context: Context): String {
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
        context.getString(R.string.model_text_only_attachment, what)
    } else {
        context.getString(
            R.string.model_attachment_unsupported,
            what,
            readable.joinToString(" and "),
        )
    }
}

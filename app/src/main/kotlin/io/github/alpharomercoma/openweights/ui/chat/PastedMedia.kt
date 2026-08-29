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

import android.content.ClipData
import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import io.github.alpharomercoma.openweights.core.common.model.MediaKind

/**
 * Pictures that arrive in the composer itself: pasted, inserted by the keyboard, or dropped.
 *
 * The attachment sheet has offered a Paste row for a while, but it is a place you have to
 * know about. Everywhere else on the phone a picture is pasted the way words are — long
 * press, Paste — and until now the field silently ignored one, which reads as the app being
 * broken rather than as the app being particular.
 *
 * What arrives here is not only the clipboard. The same listener answers a keyboard sending
 * a sticker or a GIF, and a picture dragged in from another window on a tablet or a desktop.
 * Compose asks for the permission each of those needs before handing the content over, so
 * all three land as ordinary content URIs.
 *
 * ### What is taken and what is left alone
 *
 * Only media, and only from a content provider. Text is left for the field, which already
 * pastes it; taking it here as well would put the same words in the message twice. An
 * `http` link is left for the same reason: it is a URI, but it is one the user pasted as
 * words to ask about, not a file to attach.
 *
 * Whether the *model* can read what was pasted is deliberately not asked here. A picture
 * pasted at a text-only model is staged like any other attachment and refused by [Staging]
 * with the sentence that names what this model can read, because a paste that vanishes with
 * no explanation is the failure this was written to end.
 */
internal object PastedMedia {
    /**
     * The picture, sound or video behind one pasted item, or null when it is neither.
     *
     * Takes the type lookup rather than a [ContentResolver] so the rule can be tested
     * against every shape a real paste arrives in, none of which a test can otherwise mint.
     *
     * An item carrying both a picture and text — which is what copying an image in a
     * browser produces, the text being the address it came from — is taken as the picture,
     * and the address is dropped rather than typed. That is the choice every chat app
     * makes, and the alternative is a message that says `https://…/cat.png` under a
     * photograph of the cat.
     */
    fun mediaUri(item: ClipData.Item, typeOf: (Uri) -> String?): Uri? {
        val uri = item.uri ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val type = typeOf(uri) ?: return null
        return uri.takeIf { MediaKind.of(type) != MediaKind.OTHER }
    }

    /**
     * A listener that stages every picture in a paste and hands back everything else.
     *
     * Everything else, rather than nothing, because the field is the next reader: a paste
     * carrying a caption and a photograph should attach the photograph and type the caption,
     * and returning the unconsumed remainder is how that happens.
     *
     * All the pictures, not the first: several images are one paste on every phone that can
     * select more than one, and [Attaching] already stages a list in order, refuses what
     * this model cannot read, and stops at the attachment limit with a sentence saying so.
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun listener(typeOf: (Uri) -> String?, onMedia: (List<Uri>) -> Unit) =
        ReceiveContentListener { content ->
            val pictures = mutableListOf<Uri>()
            val rest = content.consume { item ->
                val uri = mediaUri(item, typeOf)
                if (uri != null) pictures += uri
                uri != null
            }
            if (pictures.isNotEmpty()) onMedia(pictures)
            rest
        }
}

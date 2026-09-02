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

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.getSystemService
import io.github.alpharomercoma.openweights.core.common.model.MediaKind
import io.github.alpharomercoma.openweights.core.engine.MediaSupport

/**
 * Pictures and files sitting on the clipboard, and whether there is any point offering them.
 *
 * ### Why this is two functions and not one
 *
 * Reading the clipboard on Android 12 and later shows the user a system toast saying this
 * app pasted from it. That is a good rule and this code keeps to the spirit of it: reading
 * the clip's **description**, which is only its MIME types, raises no toast, and reading the
 * clip's **contents** does.
 *
 * So [holds] asks the description whether there is anything worth a row in the sheet, and
 * [read] takes the contents only after the user has tapped that row. A composer that peeked
 * at the clipboard every time it was focused would light that toast constantly and would
 * deserve to.
 */
object ClipboardMedia {
    /**
     * True when the clipboard holds something this model could accept.
     *
     * Description only, so no toast. It can be wrong in one direction: a clip whose type
     * is an image wildcard might turn out to be unreadable, and then [read] returns nothing
     * and the sheet says so. That is better than the other error, which is never offering a
     * paste for a picture that was there all along.
     */
    fun holds(context: Context, support: MediaSupport): Boolean {
        val clipboard = context.getSystemService<ClipboardManager>() ?: return false
        if (!clipboard.hasPrimaryClip()) return false
        val description = clipboard.primaryClipDescription ?: return false
        return (0 until description.mimeTypeCount).any { index ->
            support.acceptsMimeType(description.getMimeType(index))
        }
    }

    /**
     * What is actually on the clipboard, filtered to what this model can read.
     *
     * Only called from a tap, and the toast that follows is correct: the user has just asked
     * this app to take what is on their clipboard.
     *
     * Text is deliberately ignored here. A clipboard holding text is handled by the field
     * itself, which already pastes; taking it as an attachment as well would put the same
     * words in the message twice.
     */
    fun read(context: Context, support: MediaSupport): List<Uri> {
        val clipboard = context.getSystemService<ClipboardManager>() ?: return emptyList()
        val clip = clipboard.primaryClip ?: return emptyList()
        return (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it).uri }
            .filter { uri ->
                // Asking a provider what it holds is a call into another app, and one that
                // is gone or that never meant to share this URI throws rather than
                // answering; the paste receiver already guards the same call. A type that
                // cannot be resolved is refused, not waved through: nothing here can say
                // what the file is, so nothing can say this model reads it, and an
                // unreadable file staged anyway was refused again after the copy with a
                // sentence about the model rather than the clipboard.
                val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                support.acceptsMimeType(type)
            }
    }
}

/**
 * Whether this model accepts a file of the given type.
 *
 * Wildcards are accepted, because a clip description is often an image wildcard rather
 * than a specific type, and refusing the wildcard would refuse most real pastes.
 */
private fun MediaSupport.acceptsMimeType(mimeType: String?): Boolean = when (mimeType) {
    null, ClipDescription.MIMETYPE_TEXT_PLAIN, ClipDescription.MIMETYPE_TEXT_HTML -> false
    // A copied file usually arrives as a URI list rather than as its own type, because the
    // app that copied it put a reference on the clipboard and only the content resolver
    // knows what is behind it. Resolving that is a read, and a read is a toast, so the
    // description stage treats a URI list as "possibly something" and lets [read] decide.
    ClipDescription.MIMETYPE_TEXT_URILIST -> any
    else -> accepts(MediaKind.of(mimeType))
}

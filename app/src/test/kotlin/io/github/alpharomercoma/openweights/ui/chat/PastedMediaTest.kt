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
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the composer takes out of a paste, and what it leaves for the field.
 *
 * Every case here is a real shape a paste arrives in on a phone: a screenshot from the
 * gallery, a link copied from a browser, a caption and a photograph together, a document
 * from Files. The rule has to be read off the item rather than off the clip's description,
 * because a clip holding one picture and one sentence describes itself as both.
 */
@RunWith(RobolectricTestRunner::class)
class PastedMediaTest {
    private val types = mapOf(
        PICTURE to "image/png",
        RECORDING to "audio/mp4",
        CLIP to "video/mp4",
        REPORT to "application/pdf",
    )

    private val typeOf: (Uri) -> String? = { uri -> types[uri] }

    @Test
    fun `a pasted picture is taken`() {
        assertThat(PastedMedia.mediaUri(ClipData.Item(PICTURE), typeOf)).isEqualTo(PICTURE)
    }

    @Test
    fun `sound and video are taken too`() {
        // The model that can read them is the audio one, and the rule here is about what
        // this app can carry rather than about what happens to be loaded.
        assertThat(PastedMedia.mediaUri(ClipData.Item(RECORDING), typeOf)).isEqualTo(RECORDING)
        assertThat(PastedMedia.mediaUri(ClipData.Item(CLIP), typeOf)).isEqualTo(CLIP)
    }

    @Test
    fun `pasted words are left for the field`() {
        // The field already pastes text. Taking it here as well would put the same words
        // in the message twice.
        assertThat(PastedMedia.mediaUri(ClipData.Item("a question I typed elsewhere"), typeOf))
            .isNull()
    }

    @Test
    fun `a copied link is words, not a file`() {
        // A URI, and not one to attach: somebody who pastes an address is asking about it.
        val link = ClipData.Item(Uri.parse("https://example.com/a.png"))

        assertThat(PastedMedia.mediaUri(link, typeOf)).isNull()
    }

    @Test
    fun `an item carrying both a picture and its address is taken as the picture`() {
        // Copying an image in a browser produces exactly this: one item holding the file and
        // the address it came from. Attaching the picture and dropping the address is what
        // every chat app does, and the alternative is a message reading "https://…/cat.png"
        // underneath a photograph of the cat.
        val both = ClipData.Item("https://example.com/cat.png", null, PICTURE)

        assertThat(PastedMedia.mediaUri(both, typeOf)).isEqualTo(PICTURE)
    }

    @Test
    fun `a document is not media`() {
        assertThat(PastedMedia.mediaUri(ClipData.Item(REPORT), typeOf)).isNull()
    }

    @Test
    fun `a provider that will not say what it holds is left alone`() {
        // Rather than staged on the guess that it might be a picture: the attach path would
        // copy the file in before finding out, and undo it with an error nobody asked for.
        assertThat(PastedMedia.mediaUri(ClipData.Item(UNKNOWN)) { null }).isNull()
    }

    private companion object {
        val PICTURE: Uri = Uri.parse("content://media/external/images/media/42")
        val RECORDING: Uri = Uri.parse("content://media/external/audio/media/7")
        val CLIP: Uri = Uri.parse("content://media/external/video/media/9")
        val REPORT: Uri = Uri.parse("content://com.android.providers.downloads/report.pdf")
        val UNKNOWN: Uri = Uri.parse("content://some.other.app/thing")
    }
}

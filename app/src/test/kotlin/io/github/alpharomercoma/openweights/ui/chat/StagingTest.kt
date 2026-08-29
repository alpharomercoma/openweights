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
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.engine.MediaSupport
import io.github.alpharomercoma.openweights.model.AttachmentStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The capability question, asked of files that have already been copied in.
 *
 * [Attaching] asks it twice: once before the copy, on the URI, and once after, because a
 * copy suspends for as long as the file is large and a model can be switched while it runs.
 * The second ask is this, and it is tested apart from the loop because the interleaving it
 * exists for cannot be produced on demand — a copy of a test-sized file finishes far too
 * fast to still be running when the switch lands.
 */
@RunWith(RobolectricTestRunner::class)
class StagingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val staging = Staging(AttachmentStore(context), context)
    private val picture = MessagePart.File("/data/attachments/1.png", "image/png")
    private val recording = MessagePart.File("/data/attachments/1.m4a", "audio/mp4")

    @Test
    fun `a model with eyes is not told it cannot read a picture`() {
        assertThat(staging.unreadable(listOf(picture), MediaSupport(vision = true))).isNull()
    }

    @Test
    fun `a text-only model is told what it can read, not only what it cannot`() {
        val why = staging.unreadable(listOf(picture), MediaSupport())

        assertThat(why).isNotNull()
        assertThat(why).contains("text only")
    }

    @Test
    fun `the first file this model cannot read is the one named`() {
        // One sentence for the batch rather than one per file, and it has to be about a
        // file that is actually refused: an ear cannot read the picture beside the sound.
        val why = staging.unreadable(listOf(recording, picture), MediaSupport(audio = true))

        assertThat(why).contains("pictures")
        assertThat(why).contains("sound")
    }

    @Test
    fun `nothing staged is nothing to refuse`() {
        assertThat(staging.unreadable(emptyList(), MediaSupport())).isNull()
    }
}

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
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.engine.MediaSupport
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the sheet is allowed to know about the clipboard before the user taps.
 *
 * The rule being tested is a privacy one as much as a behavioural one: the description may
 * be read freely, the contents may not, and the difference is a system toast telling the
 * user this app took what they copied.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardMediaTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService<ClipboardManager>()!!
    private val vision = MediaSupport(vision = true)

    @Test
    fun `a copied file is offered, because only reading it can say what it is`() {
        clipboard.setPrimaryClip(
            ClipData.newUri(context.contentResolver, "photo", "content://pictures/1".toUri()),
        )

        assertThat(ClipboardMedia.holds(context, vision)).isTrue()
    }

    @Test
    fun `copied text is not offered as an attachment`() {
        // The field already pastes text. Taking it here too would put the same words in the
        // message twice.
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "some words"))

        assertThat(ClipboardMedia.holds(context, vision)).isFalse()
    }

    @Test
    fun `a model that reads nothing is offered nothing`() {
        clipboard.setPrimaryClip(
            ClipData.newUri(context.contentResolver, "photo", "content://pictures/1".toUri()),
        )

        assertThat(ClipboardMedia.holds(context, MediaSupport())).isFalse()
    }

    @Test
    fun `an empty clipboard offers nothing`() {
        clipboard.clearPrimaryClip()

        assertThat(ClipboardMedia.holds(context, vision)).isFalse()
    }

    @Test
    fun `reading returns the copied files`() {
        val uri = "content://pictures/7".toUri()
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "photo", uri))

        assertThat(ClipboardMedia.read(context, vision)).containsExactly(uri)
    }

    @Test
    fun `reading plain text returns nothing to attach`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "some words"))

        assertThat(ClipboardMedia.read(context, vision)).isEmpty()
    }
}

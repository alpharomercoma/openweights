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

package io.github.alpharomercoma.openweights.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What a new capture is allowed to delete.
 *
 * The camera writes into the app's own storage and [AttachmentStore.store] copies out of
 * it, so something has to sweep the originals or they accumulate, including the ones from
 * captures the user cancelled, which nothing else ever hears about. It used to sweep
 * everything, and two of those files are alive: the one the camera is writing into right
 * now, and the one a copy is still reading. Both were deletable by a second call, and both
 * lose a photograph without a word.
 *
 * Through [AttachmentStore.newCaptureFile] rather than the URI it is wrapped in, because
 * `FileProvider` caches the roots it was attached with and Robolectric hands each test a
 * different data directory: the second call in a test would fail on the provider rather
 * than on anything this is about. What the URI adds over the file is the provider, and the
 * provider is not what broke.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AttachmentStore(context)
    private val captures = File(context.filesDir, "captures")

    /** The camera writing its photograph into the file it was handed. */
    private fun capture(name: String, minutesOld: Long = 0): File = File(captures, name).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(16))
        setLastModified(System.currentTimeMillis() - minutesOld * 60L * 1000L)
    }

    @Test
    fun `a capture in flight survives the next uri`() {
        store.newCaptureFile()
        // The activity behind the camera is recreated (a rotation, a theme change, the
        // system reclaiming memory), the sheet recomposes and asks for another URI. This
        // used to delete the photograph being taken into the first one, and the result that
        // came back afterwards named the new empty file.
        val inFlight = capture("capture-in-flight.jpg")

        store.newCaptureFile()

        assertThat(inFlight.exists()).isTrue()
    }

    @Test
    fun `a capture still being copied out survives the next uri`() {
        // Copying starts on a coroutine as soon as the shutter comes back, so reopening the
        // sheet a second later used to delete the source mid-copy.
        val copying = capture("capture-copying.jpg", minutesOld = 1)

        store.newCaptureFile()

        assertThat(copying.exists()).isTrue()
    }

    @Test
    fun `a capture nothing ever came back for is swept by a later one`() {
        // The reason the sweep exists: a cancelled capture leaves a file no code path is
        // ever told about. An hour is far longer than any camera trip or any copy.
        val abandoned = capture("capture-abandoned.jpg", minutesOld = 90)

        store.newCaptureFile()

        assertThat(abandoned.exists()).isFalse()
    }
}

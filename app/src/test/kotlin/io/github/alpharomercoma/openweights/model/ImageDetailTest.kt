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
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.data.ModelPreferencesRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How large a picture is allowed to be when it reaches the model.
 *
 * This is the whole of what an image costs, and it was measured on the phone before it was
 * made a setting. The same screenshot through LFM2.5-VL-3B on a Snapdragon 8 Gen 3: at a
 * longest edge of 384 pixels the turn takes 5.8 seconds, at 1024 it takes 13.5, and at 1536
 * it takes 151. That last one is a cliff rather than a slope, because past roughly twice the
 * projector's own pixel budget libmtmd stops resizing the picture and starts cutting it into
 * 512-pixel tiles, and each tile is another 256 tokens to encode, to prefill, and to carry
 * in the cache for the rest of the conversation. See `docs/research/image-tokens.md` and
 * `ImageTokenBenchmark`.
 *
 * The store is the only place it can be enforced: the file it writes is the file every later
 * turn sends, and nothing downstream can make a picture smaller again.
 *
 * What is asserted here is the number, not the pixels. Robolectric's bitmaps are stubs with
 * no decoder behind them, so a test of the resize itself would be a test of the stub; the
 * resize is a year old and unchanged, and what is new is where its limit comes from.
 * `ImageTokenBenchmark` is where the pixels are checked, on a device, against a model.
 */
@RunWith(RobolectricTestRunner::class)
class ImageDetailTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = ModelPreferencesRepository(context)
    private val store = AttachmentStore(context, preferences)

    @Test
    fun `an install that has never opened the sheet gets the measured default`() = runBlocking {
        assertThat(store.imageEdge()).isEqualTo(ModelPreferences.DEFAULT_IMAGE_EDGE)
        assertThat(ModelPreferences.DEFAULT_IMAGE_EDGE).isEqualTo(1024)
    }

    @Test
    fun `a picture is shrunk to the size the setting asks for`() = runBlocking {
        preferences.save("", ModelPreferences(imageEdgePixels = 512))

        assertThat(store.imageEdge()).isEqualTo(512)
    }

    @Test
    fun `somebody who wants the fine print gets the pixels to read it with`() = runBlocking {
        // The other end of the same trade, and why the ceiling is above the default: at this
        // size the projector tiles, which is several times slower and is the only thing
        // measured to read twenty-four point type off a phone screenshot.
        preferences.save("", ModelPreferences(imageEdgePixels = 1536))

        assertThat(store.imageEdge()).isEqualTo(1536)
    }

    @Test
    fun `a size written into the settings file outside the range is still enforced`() =
        runBlocking {
            // A preferences file is an ordinary file. A value from a build that offered a
            // different range, or from anything editing it, must shrink a photograph oddly
            // rather than hand the decoder a zero or ask a phone for sixteen megapixels.
            preferences.save("", ModelPreferences(imageEdgePixels = 40_000))
            assertThat(store.imageEdge()).isEqualTo(ModelPreferences.MAX_IMAGE_EDGE)

            preferences.save("", ModelPreferences(imageEdgePixels = 0))
            assertThat(store.imageEdge()).isEqualTo(ModelPreferences.MIN_IMAGE_EDGE)
        }

    @Test
    fun `every size the slider offers is one the store will actually use`() = runBlocking {
        // The two halves have to agree. A stop the store then clamps away is a slider
        // position that silently does nothing, which is the failure this pins.
        for (edge in ModelPreferences.IMAGE_EDGE_STEPS) {
            preferences.save("", ModelPreferences(imageEdgePixels = edge))
            assertThat(store.imageEdge()).isEqualTo(edge)
        }
    }
}

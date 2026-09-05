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
 * How many pixels a picture keeps when it reaches the model, which is what a picture costs.
 *
 * The projector decides by area, not by edge: past twice its ceiling's pixels it cuts the
 * picture into 512-pixel tiles of 256 tokens each, and a 3:4 photograph shrunk to a 1024
 * edge was over that line while a tall screenshot at the same edge was under it. That was
 * the reported case, 2,851 tokens and three and a half minutes for one poster. So the
 * setting is a token count, the store turns it into an area, and the engine raises LFM2's
 * single-view ceiling so the middle stop is one encode. See `docs/research/image-tokens.md`.
 *
 * The store is the only place it can be enforced: the file it writes is the file every later
 * turn sends, and nothing downstream can make a picture smaller again.
 *
 * What is asserted here is the number, not the pixels. Robolectric's bitmaps are stubs with
 * no decoder behind them, so a test of the resize itself would be a test of the stub.
 * `ImageTokenBenchmark` is where the pixels are checked, on a device, against a model.
 */
@RunWith(RobolectricTestRunner::class)
class ImageDetailTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = ModelPreferencesRepository(context)
    private val store = AttachmentStore(context, preferences)

    @Test
    fun `an install that has never opened the sheet gets the balanced view`() = runBlocking {
        // The settings store is process-wide under Robolectric, so a sibling test's save can
        // be sitting in it; the defaults are asserted on a fresh object and on the store
        // after the defaults are written, which is what a new install's first save does.
        assertThat(ModelPreferences().imageTokens).isEqualTo(ModelPreferences.IMAGE_TOKENS_BALANCED)
        preferences.save("", ModelPreferences())
        assertThat(store.imagePixels()).isEqualTo(512 * 1024)
    }

    @Test
    fun `every stop buys the pixels its tokens name, and only those`() = runBlocking {
        // A token is 1024 pixels on this projector. Sending more than the budget is pixels
        // the projector throws away; sending more than twice the ceiling is tiles.
        preferences.save("", ModelPreferences(imageTokens = ModelPreferences.IMAGE_TOKENS_FAST))
        assertThat(store.imagePixels()).isEqualTo(256 * 1024)

        preferences.save("", ModelPreferences(imageTokens = ModelPreferences.IMAGE_TOKENS_BALANCED))
        assertThat(store.imagePixels()).isEqualTo(512 * 1024)
    }

    @Test
    fun `the tiles stop sends enough to cross the tiling line for any phone shape`() = runBlocking {
        // The engine raises the ceiling to 512 tokens, which puts the line at 1,048,576
        // pixels. Anything the projector rounds to more than that tiles; 2.5 megapixels
        // is over it for a square, a 3:4, a 16:9 and a 9:20 alike.
        preferences.save("", ModelPreferences(imageTokens = ModelPreferences.IMAGE_TOKENS_TILES))
        assertThat(store.imagePixels()).isGreaterThan(2 * 512 * 1024)
    }

    @Test
    fun `a value that is not a stop reads as the default`() = runBlocking {
        // A preferences file is an ordinary file. A value from a build that offered a
        // different range, or from anything editing it, must not hand the decoder a zero or
        // ask a phone for sixteen megapixels.
        preferences.save("", ModelPreferences(imageTokens = 40_000))
        assertThat(store.imagePixels()).isEqualTo(512 * 1024)

        preferences.save("", ModelPreferences(imageTokens = 0))
        assertThat(store.imagePixels()).isEqualTo(512 * 1024)
    }

    @Test
    fun `every stop the slider offers is one the store will actually use`() = runBlocking {
        // The two halves have to agree. A stop the store then reads as the default is a
        // slider position that silently does nothing, which is the failure this pins.
        val budgets = ModelPreferences.IMAGE_TOKEN_STEPS.map { tokens ->
            preferences.save("", ModelPreferences(imageTokens = tokens))
            store.imagePixels()
        }
        assertThat(budgets).containsNoDuplicates()
        assertThat(budgets).isInStrictOrder()
    }

    @Test
    fun `the old longest edge setting migrates to the nearest stop`() = runBlocking {
        // Below the old default the person wanted speed; above it, the only way to tiles
        // was the two large stops, and somebody who chose them chose the wait.
        preferences.save("", ModelPreferences(imageEdgePixels = 512, imageTokens = 0))
        assertThat(preferences.current("").imageTokens)
            .isEqualTo(ModelPreferences.IMAGE_TOKENS_FAST)
        assertThat(preferences.current("").imageEdgePixels).isEqualTo(0)

        preferences.save("", ModelPreferences(imageEdgePixels = 1024, imageTokens = 0))
        assertThat(preferences.current("").imageTokens)
            .isEqualTo(ModelPreferences.IMAGE_TOKENS_BALANCED)

        preferences.save("", ModelPreferences(imageEdgePixels = 1536, imageTokens = 0))
        assertThat(preferences.current("").imageTokens)
            .isEqualTo(ModelPreferences.IMAGE_TOKENS_TILES)
    }
}

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

package io.github.alpharomercoma.openweights.core.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The square the encoder takes, with the picture centred and the margins the corner colour. */
class LetterboxTest {

    @Test
    fun `a wide picture is centred vertically with its corners' colour around it`() {
        // 4 x 2 picture, all one colour, on a 4-square: one row of margin above and below.
        val red = 0xFF0000
        val pixels = IntArray(8) { red }

        val out = Letterbox.pack(pixels, width = 4, height = 2, side = 4)

        val plane = 16
        assertThat(out).hasLength(3 * plane)
        // Row 1 and 2 hold the picture; rows 0 and 3 are margin in the same colour.
        for (i in 0 until plane) {
            assertThat(out[i]).isEqualTo(255f)
            assertThat(out[plane + i]).isEqualTo(0f)
            assertThat(out[2 * plane + i]).isEqualTo(0f)
        }
    }

    @Test
    fun `channels come out planar in RGB order, 0 to 255`() {
        val pixel = (10 shl 16) or (20 shl 8) or 30
        val out = Letterbox.pack(intArrayOf(pixel), width = 1, height = 1, side = 1)

        assertThat(out.toList()).containsExactly(10f, 20f, 30f).inOrder()
    }

    @Test
    fun `the margin is the mean of the four corners`() {
        // 2 x 1: a black and a white pixel. Corners are those two, twice each: mean 127.
        val out = Letterbox.pack(intArrayOf(0x000000, 0xFFFFFF), width = 2, height = 1, side = 2)

        // Row 0 is the picture (top = (2 - 1) / 2 = 0), row 1 is margin.
        assertThat(out[0]).isEqualTo(0f)
        assertThat(out[1]).isEqualTo(255f)
        assertThat(out[2]).isEqualTo(127f)
        assertThat(out[3]).isEqualTo(127f)
    }
}

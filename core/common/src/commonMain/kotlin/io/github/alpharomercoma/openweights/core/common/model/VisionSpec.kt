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

package io.github.alpharomercoma.openweights.core.common.model

/**
 * How a family's compiled vision export takes a picture.
 *
 * None of this is in the file where the app could read it: the Android runtime reports a
 * method's name and backend and not its input shape, and the tokens around a picture are
 * the processor's convention, not the graph's. So each family that has an export this app
 * can feed states the four facts here, taken from the export's own configuration and from
 * the runner its publisher ships.
 *
 * @property side the square the encoder takes, in pixels. The picture is letterboxed onto it.
 * @property tokens the positions one picture occupies in the decoder's cache.
 * @property before the text that precedes the picture, as the processor writes it.
 * @property after the text that follows it.
 * @property pixels the range the encoder expects its input in.
 * @property fit how a picture that is not square is put on the square.
 */
data class VisionSpec(
    val side: Int,
    val tokens: Int,
    val before: String,
    val after: String,
    val pixels: PixelRange,
    val fit: Fit,
)

/**
 * How a picture is placed on the encoder's square. Processors differ, and the encoder was
 * trained on one of them: Software Mansion's runner letterboxes for LFM2.5-VL, while
 * Gemma 3's processor resizes straight to 896 by 896 with no regard for aspect (codex QA:
 * letterboxing a 16:9 photo for Gemma would hand the encoder 44% padding it never saw).
 */
enum class Fit {
    /** Scaled to fit, centred, margins filled from the picture's corners. */
    LETTERBOX,

    /** Scaled to the square in both directions, aspect not preserved. */
    STRETCH,
}

/**
 * What one channel value looks like at the encoder's input.
 *
 * Exports differ on whether the processor's rescale and normalise steps were baked into
 * the graph. Software Mansion's LFM2.5-VL bakes them and takes the raw bytes; the official
 * Gemma 3 export from optimum-executorch wraps the vision tower alone and takes what the
 * processor would have produced, which for Gemma 3 is mean 0.5 and standard deviation 0.5.
 */
enum class PixelRange {
    /** 0 to 255, the bytes as decoded. */
    RAW,

    /** 0 to 1, the bytes divided by 255. */
    UNIT,

    /** -1 to 1, the bytes divided by 255 then centred on 0.5 and doubled. */
    SIGNED,
    ;

    /** [raw] 0..255 channel values rewritten in place into this range, and returned. */
    fun applyTo(raw: FloatArray): FloatArray {
        when (this) {
            RAW -> Unit
            UNIT -> for (i in raw.indices) raw[i] = raw[i] / MAX_BYTE
            SIGNED -> for (i in raw.indices) raw[i] = (raw[i] / MAX_BYTE - HALF) / HALF
        }
        return raw
    }

    private companion object {
        const val MAX_BYTE = 255f
        const val HALF = 0.5f
    }
}

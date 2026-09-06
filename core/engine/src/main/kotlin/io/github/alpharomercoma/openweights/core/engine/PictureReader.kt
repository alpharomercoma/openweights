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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlin.math.roundToInt

/**
 * Turns a picture on disk into the square of raw pixels a compiled vision encoder takes.
 *
 * An interface rather than a function so the engine can be tested without a bitmap
 * decoder, which the JVM does not have.
 */
fun interface PictureReader {
    /** Channels-first floats in 0..255, `3 * side * side` of them, or null if unreadable. */
    fun read(path: String, side: Int): FloatArray?
}

/**
 * The letterbox: the picture scaled to fit a square, centred, the margins filled.
 *
 * What Software Mansion's runner does for these exports (`resizePadded`, then RGB, then
 * channels-first), reproduced here because the encoder was trained on it. The margin
 * colour is the mean of the four corner pixels, which blends into a photo's edges better
 * than flat grey and matches what their runner picks.
 */
object Letterbox {
    /**
     * Places [argb] pixels of [width] x [height], already scaled to fit, on a [side] square.
     *
     * Pure, so it can be tested: the caller has done the scaling.
     */
    fun pack(argb: IntArray, width: Int, height: Int, side: Int): FloatArray {
        require(width in 1..side && height in 1..side) { "scaled picture must fit the square" }
        require(argb.size >= width * height) { "not enough pixels for $width x $height" }
        val plane = side * side
        val out = FloatArray(CHANNELS * plane)
        val pad = padColour(argb, width, height)
        val left = (side - width) / 2
        val top = (side - height) / 2
        for (y in 0 until side) {
            for (x in 0 until side) {
                val sx = x - left
                val sy = y - top
                val pixel = if (sx in 0 until width &&
                    sy in 0 until height
                ) {
                    argb[sy * width + sx]
                } else {
                    pad
                }
                val i = y * side + x
                out[i] = ((pixel shr RED_SHIFT) and CHANNEL_MASK).toFloat()
                out[plane + i] = ((pixel shr GREEN_SHIFT) and CHANNEL_MASK).toFloat()
                out[2 * plane + i] = (pixel and CHANNEL_MASK).toFloat()
            }
        }
        return out
    }

    private fun padColour(argb: IntArray, width: Int, height: Int): Int {
        val corners = intArrayOf(
            argb[0],
            argb[width - 1],
            argb[(height - 1) * width],
            argb[height * width - 1],
        )
        fun channel(shift: Int) = corners.sumOf { (it shr shift) and CHANNEL_MASK } / corners.size
        return (channel(RED_SHIFT) shl RED_SHIFT) or (channel(GREEN_SHIFT) shl GREEN_SHIFT) or
            channel(0)
    }

    /** RGB, which is what the encoder takes and what an ARGB int carries after the alpha byte. */
    const val CHANNELS = 3
    private const val CHANNEL_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}

/** [this] turned the way [exif] says, or itself when there is no tag or it says nothing. */
fun Bitmap.upright(exif: ExifInterface?): Bitmap {
    val orientation = exif?.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    ) ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(QUARTER_TURN)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(HALF_TURN)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(THREE_QUARTER_TURN)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(QUARTER_TURN)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(THREE_QUARTER_TURN)
            matrix.postScale(-1f, 1f)
        }
        else -> return this
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private const val QUARTER_TURN = 90f
private const val HALF_TURN = 180f
private const val THREE_QUARTER_TURN = 270f

/** The reader the app uses: Android's decoder, scaled to fit, then [Letterbox]. */
class AndroidPictureReader : PictureReader {
    override fun read(path: String, side: Int): FloatArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decode at the smallest power-of-two reduction that still leaves the longer edge
        // at least the square's side, so a 12-megapixel photo is not decoded whole.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(path, options) ?: return null
        // A phone camera stores a portrait photo as landscape pixels and a tag saying to
        // turn it. The decoder does not read the tag; the encoder would see the picture
        // on its side.
        val decoded = raw.upright(runCatching { ExifInterface(path) }.getOrNull())
        if (decoded !== raw) raw.recycle()
        try {
            val scale = side.toFloat() / maxOf(decoded.width, decoded.height)
            val width = (decoded.width * scale).roundToInt().coerceIn(1, side)
            val height = (decoded.height * scale).roundToInt().coerceIn(1, side)
            val scaled = if (width == decoded.width && height == decoded.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, width, height, true)
            }
            try {
                val pixels = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                return Letterbox.pack(pixels, width, height, side)
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }
}

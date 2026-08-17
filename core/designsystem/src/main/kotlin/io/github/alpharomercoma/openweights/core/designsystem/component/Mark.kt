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

package io.github.alpharomercoma.openweights.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsColors
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme

/**
 * The mark: three bars, which read as weights.
 *
 * The same drawing as the launcher icon and the 512 the store shows, which is the only
 * reason this exists as a composable rather than as a drawable resource. Those two are a
 * vector and a PNG produced by a script, and until now the app itself had no copy at all,
 * so the About screen was the one place the product could not show its own mark.
 *
 * Lime on ink, both palettes, no light variant. An accent tile reads on a white canvas and
 * on a near-black one, and a mark that changed colour with the theme would be the single
 * element on the screen that did.
 *
 * Geometry is stated once here, in fractions of the longest bar, and every other renderer
 * uses the same three numbers: `ic_launcher_foreground.xml` at a content width of 50 in a
 * 108 viewport, and `play/graphics/mark.py` at 272 in 512. They have drifted before, which
 * is what the comments in both of those are still apologising for.
 */
@Composable
fun Mark(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension
        drawRoundRect(
            color = OpenWeightsColors.Ink,
            size = Size(side, side),
            cornerRadius = CornerRadius(side * TILE_CORNER),
        )

        // Wider than the launcher's, because nothing here is going to be cut to a circle
        // by somebody's home screen.
        val longest = side * CONTENT_WIDTH
        val barHeight = longest * BAR_HEIGHT
        val pitch = longest * BAR_PITCH
        val left = (side - longest) / 2
        val middle = side / 2

        BARS.forEachIndexed { index, widthFraction ->
            val top = middle + (index - 1) * pitch - barHeight / 2
            drawRoundRect(
                color = OpenWeightsColors.Lime,
                topLeft = Offset(left, top),
                size = Size(longest * widthFraction, barHeight),
                cornerRadius = CornerRadius(barHeight / 2),
            )
        }
    }
}

/**
 * Bar lengths, as fractions of the longest.
 *
 * Descending and left aligned, so it reads as three quantities rather than as a menu. One
 * colour and no value ladder: a third bar dimmed to imply depth goes muddy at launcher size
 * and stops reading at all, which was measured at 96px rather than argued about.
 */
val BARS = listOf(1f, 0.70f, 0.41f)

/** Bar thickness, as a fraction of the longest bar. */
const val BAR_HEIGHT = 0.22f

/** Centre to centre, as a fraction of the longest bar. */
const val BAR_PITCH = 0.32f

/** How much of the tile the longest bar spans. */
const val CONTENT_WIDTH = 0.62f

/** Corner radius, as a fraction of the tile. Enough to soften, not enough to read as round. */
const val TILE_CORNER = 0.24f

@Preview
@Composable
private fun MarkPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        Mark(size = 96.dp)
    }
}

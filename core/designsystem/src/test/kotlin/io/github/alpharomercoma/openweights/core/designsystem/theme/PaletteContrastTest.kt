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

package io.github.alpharomercoma.openweights.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The palette's promises, measured.
 *
 * Every ratio in `Color.kt`'s documentation was computed by hand once and then written down,
 * which is the same as not having been computed at all: the next person to nudge a grey has
 * no way to find out what they broke. WCAG's relative luminance is twenty lines, so the
 * claims can simply be true on every build instead.
 *
 * Two thresholds, and they are the standard's rather than a preference. Body text needs
 * 4.5:1. Anything that is a shape rather than a word, a hairline, a bar, an icon, needs 3:1.
 */
class PaletteContrastTest {
    @Test
    fun `ink on lime carries the accent in both themes`() {
        // The first rule of the palette, and the reason nothing writes white on lime.
        assertThat(contrast(OpenWeightsColors.Ink, OpenWeightsColors.Lime)).isAtLeast(BODY)
        assertThat(contrast(Color.White, OpenWeightsColors.Lime)).isLessThan(SHAPE)
    }

    @Test
    fun `lime is unreadable as text on paper, which is why it is never used that way`() {
        // Not a bug being tolerated: an assertion that the fact behind the rule still holds.
        // If a future lime cleared 3:1 on white the rule could be relaxed, and this test is
        // where anybody would find that out.
        assertThat(contrast(OpenWeightsColors.Lime, OpenWeightsColors.PaperCanvas))
            .isLessThan(SHAPE)
    }

    @Test
    fun `the primary role is legible as ink on its own canvas`() {
        // Material paints `primary` as a word in a text button, as a track in a slider and
        // as a caret in a text field. Whatever it is set to has to survive being ink, which
        // is the whole reason the light scheme does not use lime for it.
        assertThat(contrast(DarkColorScheme.primary, DarkColorScheme.surface)).isAtLeast(BODY)
        assertThat(contrast(LightColorScheme.primary, LightColorScheme.surface)).isAtLeast(BODY)
    }

    @Test
    fun `body text clears 4,5 to 1 on every surface it is drawn on`() {
        val pairs = listOf(
            DarkColorScheme.onSurface to DarkColorScheme.surface,
            DarkColorScheme.onSurfaceVariant to DarkColorScheme.surface,
            DarkColorScheme.onSurfaceVariant to DarkColorScheme.surfaceContainer,
            DarkColorScheme.onSurfaceVariant to DarkColorScheme.surfaceContainerHigh,
            LightColorScheme.onSurface to LightColorScheme.surface,
            LightColorScheme.onSurfaceVariant to LightColorScheme.surface,
            LightColorScheme.onSurfaceVariant to LightColorScheme.surfaceContainer,
            LightColorScheme.onSurfaceVariant to LightColorScheme.surfaceContainerHigh,
        )
        pairs.forEach { (ink, canvas) ->
            assertThat(contrast(ink, canvas)).isAtLeast(BODY)
        }
    }

    @Test
    fun `an outline is often the only thing saying where a control begins`() {
        listOf(
            DarkColorScheme.outline to DarkColorScheme.surface,
            DarkColorScheme.outline to DarkColorScheme.surfaceContainer,
            LightColorScheme.outline to LightColorScheme.surface,
            LightColorScheme.outline to LightColorScheme.surfaceContainer,
        ).forEach { (line, canvas) ->
            assertThat(contrast(line, canvas)).isAtLeast(SHAPE)
        }
    }

    @Test
    fun `the whole signal scale reads on both canvases`() {
        // Sampled rather than spot checked at the three stops, because the scale is two
        // interpolations and the dimmest point of it is somewhere in the middle rather than
        // at either end.
        (0..STOPS).forEach { step ->
            val fraction = step.toFloat() / STOPS
            assertThat(contrast(signalColor(fraction, dark = true), DarkColorScheme.surface))
                .isAtLeast(SHAPE)
            assertThat(contrast(signalColor(fraction, dark = false), LightColorScheme.surface))
                .isAtLeast(SHAPE)
        }
    }

    @Test
    fun `error text survives on the surfaces it warns from`() {
        assertThat(contrast(DarkColorScheme.error, DarkColorScheme.surfaceContainer))
            .isAtLeast(SHAPE)
        assertThat(contrast(LightColorScheme.error, LightColorScheme.surfaceContainer))
            .isAtLeast(SHAPE)
    }

    /** WCAG 2.1, with the lighter of the two on top. Ratios run from 1:1 to 21:1. */
    private fun contrast(a: Color, b: Color): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (max(first, second) + OFFSET) / (min(first, second) + OFFSET)
    }

    /** Relative luminance: sRGB linearised, then weighted the way an eye weights it. */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= LINEAR_CUTOFF) v / LINEAR_DIVISOR else ((v + A) / (1 + A)).pow(GAMMA)
        }
        return RED * channel(color.red) +
            GREEN * channel(color.green) +
            BLUE * channel(color.blue)
    }

    private companion object {
        const val BODY = 4.5
        const val SHAPE = 3.0

        /** How many points to sample along the signal scale. */
        const val STOPS = 20

        const val OFFSET = 0.05
        const val LINEAR_CUTOFF = 0.03928
        const val LINEAR_DIVISOR = 12.92
        const val A = 0.055
        const val GAMMA = 2.4
        const val RED = 0.2126
        const val GREEN = 0.7152
        const val BLUE = 0.0722
    }
}

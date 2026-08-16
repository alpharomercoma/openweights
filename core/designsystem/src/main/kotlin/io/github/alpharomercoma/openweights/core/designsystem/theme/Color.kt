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

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Lime on ink, with hueless greys between.
 *
 * Ported from `alpharomercoma/portfolio-unleashed`, whose `docs/design-system.md` and
 * `globals.css` are the only places that project defines colour. Three colours and nothing
 * else: white or near-black as the canvas, [Ink] for text and dark panels, and [Lime] used
 * boldly rather than as a garnish. Every grey here is neutral on purpose, so lime is the one
 * chromatic note in the chrome and reads as "you can act here" wherever it appears.
 *
 * Three rules come with it and are not negotiable, because two of them are contrast facts:
 *
 * 1. **Ink on lime, never white.** Lime is a light colour. Ink on it measures 12.99:1; white
 *    on it would be unreadable.
 * 2. **Lime is never text or a meaningful icon on a light surface.** It measures 1.13:1 on
 *    white, which is invisible rather than merely poor. A lime word on a light background
 *    means a lime fill carrying ink text.
 * 3. **Measurement is a separate language.** [signalColor] runs teal to grey to red and
 *    never passes through lime. That rule predates this palette: an earlier build put the
 *    accent at the midpoint of the throughput scale and the rail read as *selected* before
 *    it read as *12 tok/s*. Lime is a worse offender than brass was, being both the action
 *    colour and the obvious choice for "fast".
 *
 * Every pair was computed rather than eyeballed. Body text clears 4.5:1 and [Outline] clears
 * 3:1 against canvas, raised and raised-high alike, because a hairline border is often the
 * only thing saying where a control begins.
 */
object OpenWeightsColors {
    /** The brand accent. A fill, a CTA, a tinted surface. Never text on a light surface. */
    val Lime = Color(0xFFE0FF4F)

    /** What sits on [Lime]: 12.99:1. Also the light theme's text colour. */
    val Ink = Color(0xFF052B42)

    // Dark. Near-black, not pure black, so panel edges do not disappear on OLED.
    val Canvas = Color(0xFF0D0E10)
    val Raised = Color(0xFF161719)
    val RaisedHigh = Color(0xFF232427)

    /** Clears 3:1 on all three dark surfaces. Bounds a control. */
    val Outline = Color(0xFF6E7178)

    /** Decorative rule, never load bearing. The portfolio's white-at-12% over [Canvas]. */
    val Divider = Color(0xFF26272A)

    val Text = Color(0xFFF5F6F3)
    val TextDim = Color(0xFFA2A4AB)

    /** A lime wash for a raised surface that has to read as active. */
    val LimeContainer = Color(0xFF33401A)

    // Light. The portfolio's base: plain white, not an off-white.
    val PaperCanvas = Color(0xFFFFFFFF)
    val PaperRaised = Color(0xFFF4F5F3)
    val PaperRaisedHigh = Color(0xFFE7E8E4)

    /** Clears 3:1 on all three light surfaces. */
    val PaperOutline = Color(0xFF7C7F86)
    val PaperDivider = Color(0xFFE7E8E4)

    val PaperText = Ink
    val PaperTextDim = Color(0xFF52555B)

    /** The portfolio's `lime-wash`, for a tinted section on white. */
    val PaperLimeContainer = Color(0xFFF3FFCE)

    // The measurement scale. Teal rather than lime, for the reason in the class comment.
    val SignalGood = Color(0xFF3BA88F)
    val SignalPlain = TextDim
    val SignalPoor = Color(0xFFFF6166)
    val Danger = SignalPoor

    /**
     * The light theme's teal is much darker than the dark theme's.
     *
     * `#3BA88F` measures 2.92:1 on white, which fails the 3:1 a meaningful colour has to
     * clear, so light gets its own value at 4.15:1. A light theme cannot carry the same
     * green, the same way it could not carry the same amber before it.
     */
    val PaperSignalGood = Color(0xFF2F8B74)
    val PaperSignalPlain = PaperTextDim
    val PaperSignalPoor = Color(0xFFE5484D)
    val PaperDanger = PaperSignalPoor
}

/**
 * Maps a normalised measurement onto the signal scale.
 *
 * Grey through the middle, because most readings are ordinary and a rail that is only
 * coloured when something is fast or struggling is a rail worth looking at.
 *
 * Read from constants rather than from the colour scheme, so wallpaper-derived dynamic
 * colour cannot redefine what a measurement looks like.
 *
 * @param fraction 0 = the poor end (slow, or context nearly full), 1 = fast and roomy.
 */
fun signalColor(fraction: Float, dark: Boolean = true): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    val poor = if (dark) OpenWeightsColors.SignalPoor else OpenWeightsColors.PaperSignalPoor
    val plain = if (dark) OpenWeightsColors.SignalPlain else OpenWeightsColors.PaperSignalPlain
    val good = if (dark) OpenWeightsColors.SignalGood else OpenWeightsColors.PaperSignalGood
    return if (clamped < SCALE_MIDPOINT) {
        lerp(poor, plain, clamped / SCALE_MIDPOINT)
    } else {
        lerp(plain, good, (clamped - SCALE_MIDPOINT) / SCALE_MIDPOINT)
    }
}

/** The scale has three stops, so each half of the input range covers one interpolation. */
private const val SCALE_MIDPOINT = 0.5f

/**
 * `primary` is lime in both themes, and that needs saying out loud.
 *
 * Material treats `primary` as both a fill and a text colour: a filled `Button` paints it
 * and writes `onPrimary` on top, which is exactly right here, but a `TextButton` writes
 * `primary` straight onto the surface, which on white would be lime at 1.13:1.
 *
 * The fill is the common case and the one the brand depends on, so `primary` stays lime and
 * text buttons carry an explicit content colour instead. Anything ghost-shaped in this app
 * uses `onSurface`; anything secondary is an outlined pill. There is no call site left that
 * paints `primary` as text on a light surface, and there must not be a new one.
 */
internal val DarkColorScheme = darkColorScheme(
    primary = OpenWeightsColors.Lime,
    onPrimary = OpenWeightsColors.Ink,
    primaryContainer = OpenWeightsColors.LimeContainer,
    onPrimaryContainer = OpenWeightsColors.Lime,
    secondary = OpenWeightsColors.TextDim,
    onSecondary = OpenWeightsColors.Canvas,
    secondaryContainer = OpenWeightsColors.RaisedHigh,
    onSecondaryContainer = OpenWeightsColors.Text,
    background = OpenWeightsColors.Canvas,
    onBackground = OpenWeightsColors.Text,
    surface = OpenWeightsColors.Canvas,
    onSurface = OpenWeightsColors.Text,
    surfaceVariant = OpenWeightsColors.Raised,
    onSurfaceVariant = OpenWeightsColors.TextDim,
    surfaceContainerLowest = OpenWeightsColors.Canvas,
    surfaceContainerLow = OpenWeightsColors.Raised,
    surfaceContainer = OpenWeightsColors.Raised,
    surfaceContainerHigh = OpenWeightsColors.RaisedHigh,
    surfaceContainerHighest = OpenWeightsColors.RaisedHigh,
    outline = OpenWeightsColors.Outline,
    outlineVariant = OpenWeightsColors.Divider,
    error = OpenWeightsColors.Danger,
    onError = OpenWeightsColors.Canvas,
)

internal val LightColorScheme = lightColorScheme(
    primary = OpenWeightsColors.Lime,
    onPrimary = OpenWeightsColors.Ink,
    primaryContainer = OpenWeightsColors.PaperLimeContainer,
    onPrimaryContainer = OpenWeightsColors.Ink,
    secondary = OpenWeightsColors.PaperTextDim,
    onSecondary = OpenWeightsColors.PaperCanvas,
    secondaryContainer = OpenWeightsColors.PaperRaisedHigh,
    onSecondaryContainer = OpenWeightsColors.PaperText,
    background = OpenWeightsColors.PaperCanvas,
    onBackground = OpenWeightsColors.PaperText,
    surface = OpenWeightsColors.PaperCanvas,
    onSurface = OpenWeightsColors.PaperText,
    surfaceVariant = OpenWeightsColors.PaperRaised,
    onSurfaceVariant = OpenWeightsColors.PaperTextDim,
    surfaceContainerLowest = OpenWeightsColors.PaperCanvas,
    surfaceContainerLow = OpenWeightsColors.PaperRaised,
    surfaceContainer = OpenWeightsColors.PaperRaised,
    surfaceContainerHigh = OpenWeightsColors.PaperRaisedHigh,
    surfaceContainerHighest = OpenWeightsColors.PaperRaisedHigh,
    outline = OpenWeightsColors.PaperOutline,
    outlineVariant = OpenWeightsColors.PaperDivider,
    error = OpenWeightsColors.PaperDanger,
    onError = OpenWeightsColors.PaperCanvas,
)

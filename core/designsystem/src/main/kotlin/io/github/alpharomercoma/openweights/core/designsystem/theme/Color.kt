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
 * Brass on graphite.
 *
 * OpenWeights is an instrument you own: you chose the file, it runs on your silicon, and
 * you can watch how fast. Warm brass against a cool graphite carries that better than the
 * blue-white every assistant uses, and it keeps us out of a crowded room: ChatGPT is
 * monochrome, Claude is terracotta on cream, Gemini is Google blue, Qwen is violet.
 *
 * Three rules hold the palette together:
 *
 * 1. **One accent.** [Brass] means "you can act here" and nothing else: send, the active
 *    tab, focus, selection. The previous palette used the same teal for "fast" and for
 *    "tap me", so neither reading was reliable.
 * 2. **Measurement is a separate language.** The [signalColor] scale runs jade → slate →
 *    rust and never passes through the accent. Grey in the middle is the
 *    point: most readings are unremarkable, so a coloured one means something.
 * 3. **Colour is never the only signal.** Every telemetry colour sits beside the number it
 *    describes. Hue is the glance; the digits are the answer.
 *
 * Every pair below was checked against WCAG 2.2: body text and accents clear 4.5:1 on the
 * surfaces they appear on, and [Outline] clears 3:1, the non-text threshold, against
 * canvas, raised and raised-high alike, because a border is often the only thing that says
 * where a control begins.
 */
object OpenWeightsColors {
    // 60%. The canvas. Near-black with a cool graphite bias, not pure black: OLED-friendly
    // without the flatness that makes every panel edge disappear.
    val Canvas = Color(0xFF0B0D0F)

    // 30%: raised surfaces. The step from canvas is small by design, so [Outline] rather
    // than fill is what makes a control's boundary legible.
    val Raised = Color(0xFF181C21)
    val RaisedHigh = Color(0xFF242A31)

    /** Interactive boundaries. Passes 3:1 on every surface it can sit against. */
    val Outline = Color(0xFF6A7783)

    /** Decorative rules between rows. Quiet; never the only boundary. */
    val Divider = Color(0xFF20262C)

    val Text = Color(0xFFECF1F4)
    val TextDim = Color(0xFF9AA6AF)

    // 10%. The accent, and only ever for action.
    val Brass = Color(0xFFF0A93B)

    /** Text and icons drawn on top of a [Brass] fill. Near-black, 9.6:1. */
    val OnBrass = Color(0xFF120E06)

    /** A brass wash for selected rows and containers, where a full fill would shout. */
    val BrassContainer = Color(0xFF3A2A0E)

    // Measurement scale. Never used for chrome.
    val SignalGood = Color(0xFF4FC08D)
    val SignalPlain = Color(0xFF8C99A4)
    val SignalPoor = Color(0xFFFF6F59)

    val Danger = Color(0xFFFF6F59)

    // Light theme. Not a tint of the dark one: the accent has to darken considerably to
    // stay readable as text on a pale canvas, and the signal hues darken with it.
    val PaperCanvas = Color(0xFFFBFBF9)
    val PaperRaised = Color(0xFFF0F0EB)
    val PaperRaisedHigh = Color(0xFFE3E3DD)
    val PaperOutline = Color(0xFF7A8188)
    val PaperDivider = Color(0xFFDCDCD6)
    val PaperText = Color(0xFF14181B)
    val PaperTextDim = Color(0xFF535E67)
    val PaperBrass = Color(0xFF8A5A0E)
    val PaperOnBrass = Color(0xFFFFFFFF)
    val PaperBrassContainer = Color(0xFFF6E6C8)
    val PaperSignalGood = Color(0xFF0E7A55)
    val PaperSignalPlain = Color(0xFF5C666E)
    val PaperSignalPoor = Color(0xFFB23A22)
    val PaperDanger = Color(0xFFB23A22)
}

/**
 * Maps a normalised measurement onto the signal scale.
 *
 * Grey through the middle rather than amber: the accent already owns amber, and most
 * readings are ordinary. A rail that is only coloured when something is fast or
 * struggling is a rail easy to notice.
 *
 * These values come from constants rather than the colour scheme, so wallpaper-derived
 * dynamic colour cannot redefine what a measurement looks like.
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

internal val DarkColorScheme = darkColorScheme(
    primary = OpenWeightsColors.Brass,
    onPrimary = OpenWeightsColors.OnBrass,
    primaryContainer = OpenWeightsColors.BrassContainer,
    onPrimaryContainer = OpenWeightsColors.Brass,
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
    primary = OpenWeightsColors.PaperBrass,
    onPrimary = OpenWeightsColors.PaperOnBrass,
    primaryContainer = OpenWeightsColors.PaperBrassContainer,
    onPrimaryContainer = OpenWeightsColors.PaperBrass,
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
    onError = OpenWeightsColors.PaperOnBrass,
)

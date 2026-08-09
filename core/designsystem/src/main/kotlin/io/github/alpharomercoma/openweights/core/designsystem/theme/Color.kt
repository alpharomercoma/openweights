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
 * OpenWeights reads as measuring equipment rather than a chat assistant, because that is
 * what it is: you chose the file, it runs on your silicon, and you can watch how fast.
 *
 * The palette is built around that idea. Neutrals are a cool, blue-green dark — the colour
 * of unlit instrument glass — and the accent is not a single decorative highlight but the
 * [signalColor] scale, where hue actually encodes a measurement.
 */
object OpenWeightsColors {
    // Dark neutrals (default). Not pure black: a blue-green bias gives depth without warmth.
    val Ink = Color(0xFF0A0E11)
    val Panel = Color(0xFF121A1F)
    val PanelHigh = Color(0xFF1B252B)
    val Hairline = Color(0xFF24323A)
    val TextPrimary = Color(0xFFE6EFF2)
    val TextDim = Color(0xFF8CA2AC)

    // Light neutrals.
    val Paper = Color(0xFFF6F8F8)
    val PaperPanel = Color(0xFFECF1F2)
    val PaperPanelHigh = Color(0xFFE1E9EB)
    val PaperHairline = Color(0xFFCBD8DC)
    val PaperText = Color(0xFF0B1215)
    val PaperTextDim = Color(0xFF4E6169)

    /** Signal scale: fast / comfortable. Also the app's interactive accent. */
    val SignalFast = Color(0xFF5AE0C8)

    /** Signal scale: working, but the headroom is going. */
    val SignalMid = Color(0xFFE8D26B)

    /** Signal scale: slow, or nearly out of context. */
    val SignalHot = Color(0xFFFF7A5C)

    val SignalFastOnLight = Color(0xFF0F7F6C)
    val SignalMidOnLight = Color(0xFF8A6D00)
    val SignalHotOnLight = Color(0xFFC03C1F)

    val Danger = Color(0xFFFF6B6B)
    val DangerOnLight = Color(0xFFB3261E)
}

/**
 * Maps a normalised measurement onto the signal scale.
 *
 * @param fraction 0 = the hot end (slow, or context nearly full), 1 = the fast, roomy end.
 */
fun signalColor(fraction: Float, dark: Boolean = true): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    val hot = if (dark) OpenWeightsColors.SignalHot else OpenWeightsColors.SignalHotOnLight
    val mid = if (dark) OpenWeightsColors.SignalMid else OpenWeightsColors.SignalMidOnLight
    val fast = if (dark) OpenWeightsColors.SignalFast else OpenWeightsColors.SignalFastOnLight
    return if (clamped < SCALE_MIDPOINT) {
        lerp(hot, mid, clamped / SCALE_MIDPOINT)
    } else {
        lerp(mid, fast, (clamped - SCALE_MIDPOINT) / SCALE_MIDPOINT)
    }
}

/** The scale has three stops, so each half of the input range covers one interpolation. */
private const val SCALE_MIDPOINT = 0.5f

internal val DarkColorScheme = darkColorScheme(
    primary = OpenWeightsColors.SignalFast,
    onPrimary = OpenWeightsColors.Ink,
    primaryContainer = OpenWeightsColors.PanelHigh,
    onPrimaryContainer = OpenWeightsColors.SignalFast,
    secondary = OpenWeightsColors.SignalMid,
    onSecondary = OpenWeightsColors.Ink,
    background = OpenWeightsColors.Ink,
    onBackground = OpenWeightsColors.TextPrimary,
    surface = OpenWeightsColors.Ink,
    onSurface = OpenWeightsColors.TextPrimary,
    surfaceVariant = OpenWeightsColors.Panel,
    onSurfaceVariant = OpenWeightsColors.TextDim,
    surfaceContainerLowest = OpenWeightsColors.Ink,
    surfaceContainerLow = OpenWeightsColors.Panel,
    surfaceContainer = OpenWeightsColors.Panel,
    surfaceContainerHigh = OpenWeightsColors.PanelHigh,
    surfaceContainerHighest = OpenWeightsColors.PanelHigh,
    outline = OpenWeightsColors.Hairline,
    outlineVariant = OpenWeightsColors.Hairline,
    error = OpenWeightsColors.Danger,
    onError = OpenWeightsColors.Ink,
)

internal val LightColorScheme = lightColorScheme(
    primary = OpenWeightsColors.SignalFastOnLight,
    onPrimary = Color.White,
    primaryContainer = OpenWeightsColors.PaperPanelHigh,
    onPrimaryContainer = OpenWeightsColors.SignalFastOnLight,
    secondary = OpenWeightsColors.SignalMidOnLight,
    onSecondary = Color.White,
    background = OpenWeightsColors.Paper,
    onBackground = OpenWeightsColors.PaperText,
    surface = OpenWeightsColors.Paper,
    onSurface = OpenWeightsColors.PaperText,
    surfaceVariant = OpenWeightsColors.PaperPanel,
    onSurfaceVariant = OpenWeightsColors.PaperTextDim,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = OpenWeightsColors.PaperPanel,
    surfaceContainer = OpenWeightsColors.PaperPanel,
    surfaceContainerHigh = OpenWeightsColors.PaperPanelHigh,
    surfaceContainerHighest = OpenWeightsColors.PaperPanelHigh,
    outline = OpenWeightsColors.PaperHairline,
    outlineVariant = OpenWeightsColors.PaperHairline,
    error = OpenWeightsColors.DangerOnLight,
    onError = Color.White,
)

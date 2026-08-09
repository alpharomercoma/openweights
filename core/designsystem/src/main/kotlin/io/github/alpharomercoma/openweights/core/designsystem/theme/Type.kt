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

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.alpharomercoma.openweights.core.designsystem.R

/**
 * IBM Plex, chosen because it was drawn for machines and their readouts — the same job this
 * app has. Plex Sans carries the interface; Plex Mono carries every number, model id, and
 * quantization tag, so measurements are visually separable from prose at a glance.
 */
@OptIn(ExperimentalTextApi::class)
private val PlexSans = FontFamily(
    Font(R.font.plex_sans, FontWeight.Normal, variationSettings = weightAxis(400)),
    Font(R.font.plex_sans, FontWeight.Medium, variationSettings = weightAxis(500)),
    Font(R.font.plex_sans, FontWeight.SemiBold, variationSettings = weightAxis(600)),
    Font(R.font.plex_sans, FontWeight.Bold, variationSettings = weightAxis(700)),
)

private fun weightAxis(weight: Int) = FontVariation.Settings(FontVariation.weight(weight))

/** Monospace family for anything the user might compare row to row. */
val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

internal val OpenWeightsTypography = Typography().let { base ->
    Typography(
        displaySmall = base.displaySmall.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
        ),
        headlineLarge = base.headlineLarge.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
        ),
        titleLarge = base.titleLarge.copy(fontFamily = PlexSans, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontFamily = PlexSans, fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontFamily = PlexSans, fontWeight = FontWeight.Medium),
        // Chat replies are read for minutes at a time; generous leading earns its space.
        bodyLarge = base.bodyLarge.copy(
            fontFamily = PlexSans,
            fontSize = 16.sp,
            lineHeight = 26.sp,
        ),
        bodyMedium = base.bodyMedium.copy(fontFamily = PlexSans, lineHeight = 22.sp),
        bodySmall = base.bodySmall.copy(fontFamily = PlexSans),
        labelLarge = base.labelLarge.copy(fontFamily = PlexSans, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = PlexSans, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = PlexSans, fontWeight = FontWeight.Medium),
    )
}

/**
 * The readout style: throughput, token counts, context fill, quantization tags.
 * Slightly loose tracking so digits stay legible at 12sp on a phone.
 */
val MetricTextStyle = TextStyle(
    fontFamily = PlexMono,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.02.em,
)

/** Code blocks and inline code inside model output. */
val CodeTextStyle = TextStyle(
    fontFamily = PlexMono,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
)

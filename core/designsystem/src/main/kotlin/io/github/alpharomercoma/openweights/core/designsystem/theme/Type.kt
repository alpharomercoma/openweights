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
 * IBM Plex, chosen because it was drawn for machines and their readouts. The same job this
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

/**
 * The scale.
 *
 * Written out rather than inherited, because Material's defaults are tuned for nothing in
 * particular: `displaySmall` arrives at 36 sp, which no phone screen in this app has a use
 * for. Sizes step by roughly a fifth so adjacent roles are visibly different, and titles
 * carry negative tracking so a heading reads as a label rather than as a sentence.
 *
 * Tracking is in `em` rather than `sp`: it has to scale with the text, and a fixed `sp`
 * value would come apart at 200% font scale.
 */
internal val OpenWeightsTypography = Typography().let { base ->
    Typography(
        // Every role is set, including the ones nothing uses yet. A role left at its
        // default falls back to the platform font, which would quietly put a third family
        // on screen the first time anything reached for it.
        displayLarge = base.displayLarge.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.02).em,
        ),
        displayMedium = base.displayMedium.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.02).em,
        ),
        // The one number a screen is built around. The lifetime token count, and nothing
        // else so far. Deliberately the only style above 24 sp.
        displaySmall = base.displaySmall.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 40.sp,
            lineHeight = 46.sp,
            letterSpacing = (-0.02).em,
        ),
        headlineLarge = base.headlineLarge.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.015).em,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.015).em,
        ),
        // Screen titles.
        headlineSmall = base.headlineSmall.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            letterSpacing = (-0.01).em,
        ),
        // The model name in the top bar: the most-read title in the app.
        titleLarge = base.titleLarge.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            letterSpacing = (-0.01).em,
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            letterSpacing = (-0.01).em,
        ),
        // Section headings and card titles.
        titleSmall = base.titleSmall.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = (-0.005).em,
        ),
        // Chat replies are read for minutes at a time, so the leading earns its space
        // but 1.6 was bloating long answers, and 1.5 holds the paragraph together better.
        bodyLarge = base.bodyLarge.copy(
            fontFamily = PlexSans,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = PlexSans,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        ),
        bodySmall = base.bodySmall.copy(
            fontFamily = PlexSans,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        ),
        labelLarge = base.labelLarge.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        ),
        labelMedium = base.labelMedium.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = PlexSans,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        ),
    )
}

/**
 * The readout style: throughput, token counts, context fill, quantization tags.
 *
 * 13 sp rather than 12: these carry operational status, sometimes as the only text beside a
 * 2 dp colour rail, and a size that is comfortable in a design tool is not always
 * comfortable outdoors. Slightly loose tracking so digits stay separable.
 */
val MetricTextStyle = TextStyle(
    fontFamily = PlexMono,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.02.em,
)

/** Code blocks and inline code inside model output. */
val CodeTextStyle = TextStyle(
    fontFamily = PlexMono,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
)

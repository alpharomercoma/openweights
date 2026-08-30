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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.alpharomercoma.openweights.core.designsystem.R

/**
 * Three families, ported from `alpharomercoma/portfolio-unleashed`.
 *
 * The split is the point. [Display] is the voice: a grotesk with tight tracking, used for
 * anything that states rather than explains. [Body] carries every sentence, drawn to be read
 * at length rather than admired. [Mono] carries every number, model id and quantization tag,
 * so a measurement is separable from prose at a glance, which is the one thing this app does
 * that a hosted assistant does not.
 *
 * All three ship as single variable fonts with a weight axis, so each family is one file and
 * every weight is a variation of it rather than another download.
 */
/**
 * Each weight is a real instance of the variable font, cut in a font-family resource.
 *
 * It used to be `Font(R.font.x, FontWeight.Bold, variationSettings = weightAxis(700))`,
 * which reads correctly and does nothing: those settings are ignored on device, so every
 * declared weight resolved to the same 400 face. Nothing in this app was ever bold,
 * semibold or medium — not a heading, not a label, and not a `**word**` in a model's
 * reply. Worse than merely flat: declaring the faces also told Compose it had them, which
 * suppressed the synthetic bold that would otherwise have covered for it. `***both***`
 * looked right only because no italic face exists either, so that one path fell through to
 * synthesis and got emboldened on the way past.
 *
 * Proved on a device by rendering the same file four ways and comparing the pixels; only
 * this one and Compose's own synthesis produced any weight at all, and this one produces a
 * true instance rather than a smeared 400. See MarkdownTortureOnDeviceTest.
 */
private val Display = FontFamily(
    Font(R.font.schibsted_grotesk, FontWeight.Normal),
    Font(R.font.schibsted_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.schibsted_grotesk_bold, FontWeight.Bold),
)

private val Body = FontFamily(
    Font(R.font.hanken_grotesk, FontWeight.Normal),
    Font(R.font.hanken_grotesk_medium, FontWeight.Medium),
    Font(R.font.hanken_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.hanken_grotesk_bold, FontWeight.Bold),
)

/**
 * Monospace, for anything the user might compare row to row.
 *
 * Still named for what it is rather than for the typeface, because call sites care that the
 * digits line up and not who drew them. It was IBM Plex Mono; it is Geist Mono now, and no
 * call site had to change.
 */
val PlexMono = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
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
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.02).em,
        ),
        displayMedium = base.displayMedium.copy(
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.02).em,
        ),
        // The one number a screen is built around. The lifetime token count, and nothing
        // else so far. The only style above 24 sp.
        displaySmall = base.displaySmall.copy(
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 40.sp,
            lineHeight = 46.sp,
            letterSpacing = (-0.02).em,
        ),
        headlineLarge = base.headlineLarge.copy(
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.015).em,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.015).em,
        ),
        // Screen titles.
        headlineSmall = base.headlineSmall.copy(
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            letterSpacing = (-0.01).em,
        ),
        // The model name in the top bar: the most-read title in the app.
        titleLarge = base.titleLarge.copy(
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            letterSpacing = (-0.01).em,
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            letterSpacing = (-0.01).em,
        ),
        // Section headings and card titles.
        titleSmall = base.titleSmall.copy(
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = (-0.005).em,
        ),
        // Chat replies are read for minutes at a time, so the leading earns its space
        // but 1.6 was bloating long answers, and 1.5 holds the paragraph together better.
        bodyLarge = base.bodyLarge.copy(
            fontFamily = Body,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = Body,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        ),
        bodySmall = base.bodySmall.copy(
            fontFamily = Body,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        ),
        labelLarge = base.labelLarge.copy(
            fontFamily = Body,
            fontWeight = FontWeight.Medium,
            // Material tracks its small labels wide, and this scale had tightened every
            // other style and left these three alone, so the navigation labels were the
            // only airy text in the app. Neutral rather than negative: at twelve points
            // and below, pulling letters together costs more legibility than it buys.
            letterSpacing = 0.em,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        ),
        labelMedium = base.labelMedium.copy(
            fontFamily = Body,
            fontWeight = FontWeight.Medium,
            // Material tracks its small labels wide, and this scale had tightened every
            // other style and left these three alone, so the navigation labels were the
            // only airy text in the app. Neutral rather than negative: at twelve points
            // and below, pulling letters together costs more legibility than it buys.
            letterSpacing = 0.em,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = Body,
            fontWeight = FontWeight.Medium,
            // Material tracks its small labels wide, and this scale had tightened every
            // other style and left these three alone, so the navigation labels were the
            // only airy text in the app. Neutral rather than negative: at twelve points
            // and below, pulling letters together costs more legibility than it buys.
            letterSpacing = 0.em,
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

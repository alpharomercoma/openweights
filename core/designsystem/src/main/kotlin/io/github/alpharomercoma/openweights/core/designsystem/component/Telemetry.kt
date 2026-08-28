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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.theme.LocalIsDarkTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.MetricTextStyle
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.signalColor
import kotlin.math.roundToInt

/**
 * Decode speed at or above this reads as comfortably fast on a phone.
 *
 * The top of the signal scale wherever throughput is coloured, which is the reply rail,
 * the stats line under a reply, and the per-model breakdown on the dashboard. One number,
 * because three screens disagreeing about what counts as fast would be worse than any of
 * them being slightly wrong.
 */
const val FAST_TOKENS_PER_SECOND = 25.0

/**
 * The vertical rail beside a model reply, coloured by how fast that reply was generated.
 *
 * Scrolling back through a conversation, the rail makes performance history visible: which
 * answers came quickly and which ones crawled, without reading a single number.
 *
 * The colour is never the only signal. The measured rate is printed above every finished
 * reply, and the rail carries its own description for screen readers. Grey is the ordinary
 * case, so a rail that is actually green or red stands out.
 *
 * @param tokensPerSecond measured decode throughput, or null while generation is still
 *   warming up, in which case the rail is drawn neutral.
 */
@Composable
fun SpeedRail(tokensPerSecond: Double?, modifier: Modifier = Modifier) {
    val dark = LocalIsDarkTheme.current
    val target = when (tokensPerSecond) {
        null -> MaterialTheme.colorScheme.outline
        else -> signalColor(
            fraction = (tokensPerSecond / FAST_TOKENS_PER_SECOND).toFloat(),
            dark = dark,
        )
    }
    // Not animated. This describes a number that has already settled, and a rail drifting
    // towards its colour afterwards suggests a measurement still being taken.
    val color = target
    val description = tokensPerSecond
        ?.let { "Generated at ${it.roundToInt()} tokens per second" }
        ?: "Generating"

    Box(
        modifier = modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .clip(RoundedCornerShape(RAIL_WIDTH / 2))
            .background(color)
            .semantics { contentDescription = description },
    )
}

private val RAIL_WIDTH = 2.dp

/**
 * How full the model's context window is, drawn as a hairline that spans the screen just
 * above the composer. The place you are already looking while typing.
 *
 * Session token counts share this line rather than a row of their own — a CLI status bar's
 * `↑ input ↓ output` convention, next to the number it already keeps closest company with,
 * since both answer the same question of "what has this conversation cost so far."
 *
 * @param used tokens currently held in the KV cache.
 * @param total the context length the model was loaded with.
 * @param inputTokens this conversation's prompt tokens so far, cached and fresh together.
 *   Null hides the whole session-stats segment — a conversation with no replies yet has
 *   nothing to report and showing zeroes would read as a real, measured "nothing happened"
 *   rather than as "there is nothing to measure yet."
 * @param outputTokens this conversation's generated tokens so far.
 * @param cacheHitRate what fraction of [inputTokens] the KV cache answered for free, 0 to 1.
 *   Null omits just the `CH` segment, distinct from omitting the whole line: a model whose
 *   engine has not reported a cache figure yet is not the same as a conversation that has
 *   not started.
 */
@Composable
fun ContextMeter(
    used: Int,
    total: Int,
    modifier: Modifier = Modifier,
    inputTokens: Int? = null,
    outputTokens: Int? = null,
    cacheHitRate: Double? = null,
) {
    val fraction = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val dark = LocalIsDarkTheme.current
    // Headroom, not fill, drives the colour: a nearly full context is the hot end.
    val color = signalColor(1f - fraction, dark)
    val percent = (fraction * 100).roundToInt()
    val sessionText = sessionStatusText(inputTokens, outputTokens, cacheHitRate)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(
                    sessionText?.let {
                        "This conversation: $inputTokens tokens in, $outputTokens tokens out" +
                            (cacheHitRate?.let { rate ->
                                ", cache reused ${(rate * 100).roundToInt()} percent"
                            } ?: "")
                    },
                    "Context $percent percent full, $used of $total tokens",
                ).joinToString(". ")
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sessionText?.let { Metric(text = it, maxLines = 1) }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(METER_HEIGHT)
                .clip(RoundedCornerShape(METER_HEIGHT / 2))
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(color),
            )
        }
        // Printed, not only announced: a colour bar with no digits makes hue the only
        // thing a sighted user has to go on.
        // Named, not just numbered. A bare "10%" over a hairline at the bottom of a chat
        // screen could be a download, a battery, or how much of the answer has arrived; the
        // screen reader was told which and nobody looking at it was.
        Metric(text = "ctx $percent%", color = color, maxLines = 1)
    }
}

/**
 * `↑1.2k ↓340 · CH92%`, or null with nothing yet to report. Kept as a plain function rather
 * than inlined so the format has one place to test without standing up a Compose rule for it.
 */
internal fun sessionStatusText(
    inputTokens: Int?,
    outputTokens: Int?,
    cacheHitRate: Double?,
): String? {
    if (inputTokens == null || outputTokens == null) return null
    val tokens = "↑${formatTokenCount(inputTokens)} ↓${formatTokenCount(outputTokens)}"
    val hitRate = cacheHitRate?.let { " · CH${(it * 100).roundToInt()}%" }.orEmpty()
    return tokens + hitRate
}

/**
 * A quiet line of words under something.
 *
 * The same size and colour as [Metric] and not the same face. `Metric` is monospaced, which
 * is right for a rate, a byte count or a percentage that has to line up with the one under
 * it, and wrong for a sentence: "tokens generated on this device" and "Verifying checksum"
 * were both set in mono because [Metric] was the only small quiet style there was, and a
 * screen of prose in a monospaced face reads as terminal cosplay rather than as an app.
 *
 * The test is what the string is mostly made of. Digits and units get [Metric]. Words get
 * this.
 */
@Composable
fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A single readout: monospace value with a quiet label, e.g. `24.3 tok/s`.
 *
 * @param color overrides the text colour, normally supplied from the signal scale.
 */
@Composable
fun Metric(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        style = MetricTextStyle,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private val METER_HEIGHT = 2.dp

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun TelemetryPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        androidx.compose.foundation.layout.Column {
            ContextMeter(used = 1204, total = 4096)
            ContextMeter(
                used = 1204,
                total = 4096,
                inputTokens = 1840,
                outputTokens = 512,
                cacheHitRate = 0.92,
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.height(40.dp),
            ) {
                SpeedRail(tokensPerSecond = 28.0)
                Metric("28.4 tok/s · 0.41 s to first token")
            }
        }
    }
}

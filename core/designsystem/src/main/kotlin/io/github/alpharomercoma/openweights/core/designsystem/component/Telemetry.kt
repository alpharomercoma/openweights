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
 * @param used tokens currently held in the KV cache.
 * @param total the context length the model was loaded with.
 */
@Composable
fun ContextMeter(used: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val dark = LocalIsDarkTheme.current
    // Headroom, not fill, drives the colour: a nearly full context is the hot end.
    val color = signalColor(1f - fraction, dark)
    val percent = (fraction * 100).roundToInt()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Context $percent percent full, $used of $total tokens"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        Metric(text = "$percent%", color = color, maxLines = 1)
    }
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

@Preview(showBackground = true, backgroundColor = 0xFF0B0D0F)
@Composable
private fun TelemetryPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        androidx.compose.foundation.layout.Column {
            ContextMeter(used = 1204, total = 4096)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.height(40.dp),
            ) {
                SpeedRail(tokensPerSecond = 28.0)
                Metric("28.4 tok/s · 0.41 s to first token")
            }
        }
    }
}

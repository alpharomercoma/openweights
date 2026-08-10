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

package io.github.alpharomercoma.openweights.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.data.GrowthPoint
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.LocalIsDarkTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.signalColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The lifetime total, day by day.
 *
 * The bars below say what happened on a day. This says where the total has got to, which
 * is the number on the headline and otherwise has no history attached to it. A line that
 * only climbs is a dull shape by design: the interesting part is where it steepens, which
 * is where the phone was doing real work.
 *
 * Days with nothing on them are drawn flat rather than skipped, so the horizontal axis is
 * time rather than a list of days that happened to have rows.
 */
@Composable
fun GrowthChart(points: List<GrowthPoint>, modifier: Modifier = Modifier) {
    if (points.size < MIN_POINTS) return

    val dark = LocalIsDarkTheme.current
    val line = MaterialTheme.colorScheme.onSurface
    val floor = points.first().cumulativeTokens
    val ceiling = points.last().cumulativeTokens
    // A flat month would divide by zero. It also has nothing to show, so it is drawn along
    // the bottom rather than as a line through the middle claiming a shape it does not have.
    val span = (ceiling - floor).coerceAtLeast(1)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Metric("lifetime total over ${points.size} days")
            Metric("+${(ceiling - floor).compactTokens()} in that time")
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .padding(top = 8.dp),
        ) {
            val step = if (points.size > 1) size.width / (points.size - 1) else size.width
            val usableHeight = size.height - DOT_RADIUS_PX

            fun yFor(point: GrowthPoint): Float {
                val fraction = (point.cumulativeTokens - floor).toFloat() / span
                return size.height - fraction * usableHeight
            }

            val curve = Path().apply {
                points.forEachIndexed { index, point ->
                    val x = index * step
                    val y = yFor(point)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }

            // The fill is the same path closed along the bottom. It carries no information
            // the line does not, and exists so the climb reads as accumulation rather than
            // as a stock price.
            val fill = Path().apply {
                addPath(curve)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }

            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        line.copy(alpha = FILL_TOP_ALPHA),
                        line.copy(alpha = 0f),
                    ),
                ),
            )
            drawPath(path = curve, color = line.copy(alpha = LINE_ALPHA), style = Stroke(LINE_PX))

            // Where the total stands now. The only point on the curve worth marking, and
            // coloured by whether the last day beat the one before it.
            val last = points.last()
            drawCircle(
                color = signalColor(fraction = paceFraction(points), dark = dark),
                radius = DOT_RADIUS_PX,
                center = Offset(size.width, yFor(last)),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric(points.first().day.asShortDate())
            Metric(points.last().day.asShortDate())
        }
    }
}

/**
 * Where the last day sits against the busiest one, as 0 to 1.
 *
 * Used only to colour the end of the curve. Against the peak rather than against
 * yesterday, because one quiet Tuesday after a heavy Monday is not a problem and should
 * not be painted as one.
 */
private fun paceFraction(points: List<GrowthPoint>): Float {
    val peak = points.maxOf { it.dayTokens }
    if (peak <= 0) return 0f
    return points.last().dayTokens.toFloat() / peak
}

/** Epoch day to something short enough for a chart edge, in the reader's own locale. */
private fun Long.asShortDate(): String =
    LocalDate.ofEpochDay(this).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

/** Token counts on a chart label, where four digits are already too many. */
internal fun Long.compactTokens(): String = when {
    this >= MILLION -> String.format(Locale.getDefault(), "%.1fM", this / MILLION.toDouble())
    this >= THOUSAND -> String.format(Locale.getDefault(), "%.1fk", this / THOUSAND.toDouble())
    else -> toString()
}

/** Two points is the fewest that can be a line, and a line is the whole idea. */
private const val MIN_POINTS = 2

private val CHART_HEIGHT = 120.dp
private const val LINE_PX = 3f
private const val DOT_RADIUS_PX = 7f
private const val LINE_ALPHA = 0.85f
private const val FILL_TOP_ALPHA = 0.22f
private const val THOUSAND = 1_000
private const val MILLION = 1_000_000

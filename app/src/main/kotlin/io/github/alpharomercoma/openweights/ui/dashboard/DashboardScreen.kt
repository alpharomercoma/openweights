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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.data.DailyUsage
import io.github.alpharomercoma.openweights.core.data.ModelUsage
import io.github.alpharomercoma.openweights.core.data.UsageSummary
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.LocalIsDarkTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.designsystem.theme.signalColor
import java.util.Locale

/**
 * What this phone has actually done.
 *
 * Every number here was produced on this device and is stored only on it. That is the
 * reason it can exist at all: a hosted assistant cannot show you your own token totals
 * without those totals being someone else's data too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(summary: UsageSummary, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The top bar applies the status-bar inset itself and the app's navigation bar owns
        // the bottom one, so this scaffold must not add either — doing both is what left the
        // chrome floating away from the edges it belongs to.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Usage", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (summary.replies == 0) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Nothing generated yet. Once you chat, this is where the totals live — " +
                        "and they stay here, on the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Headline(summary)
            if (summary.perDay.size > 1) DailyChart(summary.perDay)
            ModelBreakdown(summary.perModel)
        }
    }
}

@Composable
private fun Headline(summary: UsageSummary) {
    Column {
        Text(
            text = summary.lifetimeGeneratedTokens.grouped(),
            style = MaterialTheme.typography.displaySmall,
        )
        Metric("tokens generated on this device")
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat("Replies", summary.replies.toLong().grouped(), Modifier.weight(1f))
        Stat("Chats", summary.conversations.toLong().grouped(), Modifier.weight(1f))
        Stat("Days used", summary.activeDays.toLong().grouped(), Modifier.weight(1f))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat(
            label = "Average speed",
            value = summary.averageTokensPerSecond
                ?.let { String.format(Locale.getDefault(), "%.1f tok/s", it) }
                ?: "—",
            modifier = Modifier.weight(1f),
        )
        Stat(
            label = "Time computing",
            value = summary.lifetimeInferenceMs.asDuration(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Metric(label)
    }
}

/**
 * Tokens per day.
 *
 * Deliberately a bare bar chart with no axes: the shape of the habit is the information,
 * and gridlines on a phone-width chart cost more space than they explain.
 */
@Composable
private fun DailyChart(days: List<DailyUsage>) {
    val dark = LocalIsDarkTheme.current
    val peak = days.maxOf { it.generatedTokens }.coerceAtLeast(1)

    Column {
        Metric("tokens per day · peak ${peak.grouped()}")
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .padding(top = 6.dp),
        ) {
            val slot = size.width / days.size
            val barWidth = (slot * BAR_FILL).coerceAtLeast(2f)

            days.forEachIndexed { index, day ->
                val fraction = day.generatedTokens.toFloat() / peak
                val barHeight = (size.height * fraction).coerceAtLeast(2f)
                drawRect(
                    color = signalColor(fraction, dark),
                    topLeft = Offset(index * slot, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                )
            }
        }
    }
}

@Composable
private fun ModelBreakdown(models: List<ModelUsage>) {
    if (models.isEmpty()) return
    val total = models.sumOf { it.generatedTokens }.coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("By model", style = MaterialTheme.typography.titleSmall)

        val dark = LocalIsDarkTheme.current

        models.forEach { model ->
            Column {
                Text(model.modelName, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Metric(
                        buildString {
                            append("${model.generatedTokens.grouped()} tokens")
                            append(" · ${(model.generatedTokens * 100 / total)}%")
                            append(" · ${model.replies} replies")
                        },
                    )
                    // The one measurement on this screen, so the one thing that earns the
                    // data scale. Token counts are volume, not health: colouring those by
                    // size would say a busy day was a fast one.
                    model.averageTokensPerSecond?.let { rate ->
                        Metric(
                            text = String.format(Locale.getDefault(), "· %.1f tok/s", rate),
                            color = signalColor((rate / FAST_TOKENS_PER_SECOND).toFloat(), dark),
                        )
                    }
                }
            }
        }
    }
}

private const val BAR_FILL = 0.7f
private const val MILLIS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60

/** Matches the rail in chat, so the same number means the same colour in both places. */
private const val FAST_TOKENS_PER_SECOND = 25.0

private fun Long.grouped(): String = String.format(Locale.getDefault(), "%,d", this)

private fun Long.asDuration(): String {
    val seconds = this / MILLIS_PER_SECOND
    val minutes = seconds / SECONDS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    return when {
        hours > 0 -> "${hours}h ${minutes % MINUTES_PER_HOUR}m"
        minutes > 0 -> "${minutes}m ${seconds % SECONDS_PER_MINUTE}s"
        else -> "${seconds}s"
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0E11)
@Composable
private fun DashboardScreenPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        DashboardScreen(
            summary = UsageSummary(
                lifetimePromptTokens = 18_204,
                lifetimeGeneratedTokens = 142_880,
                lifetimeInferenceMs = 8_640_000,
                replies = 214,
                conversations = 19,
                activeDays = 12,
                perDay = List(12) { DailyUsage(it.toLong(), (2000L..14000L).random()) },
                perModel = listOf(
                    ModelUsage("LFM2.5-2.6B-Q4_K_M", 120_400, 180, 16.4),
                    ModelUsage("Qwen3-1.7B-Q4_K_M", 22_480, 34, 28.1),
                ),
            ),
        )
    }
}

private fun LongRange.random(): Long = first + (last - first) / 2

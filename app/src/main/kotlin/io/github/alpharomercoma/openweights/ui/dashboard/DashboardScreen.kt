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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.data.DailyUsage
import io.github.alpharomercoma.openweights.core.data.GrowthPoint
import io.github.alpharomercoma.openweights.core.data.ModelUsage
import io.github.alpharomercoma.openweights.core.data.UsageSummary
import io.github.alpharomercoma.openweights.core.designsystem.component.FAST_TOKENS_PER_SECOND
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.LocalIsDarkTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.designsystem.theme.signalColor
import java.time.LocalDate
import java.time.format.TextStyle
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
        // the bottom one, so this scaffold must not add either, doing both is what left the
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
                    "Nothing generated yet. Once you chat, this is where the totals live: " +
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
            GrowthChart(summary.growth)
            WeekChart(summary.growth)
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
        DayChange(summary)
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
                ?: " ",
            modifier = Modifier.weight(1f),
        )
        Stat(
            label = "Time computing",
            value = summary.lifetimeInferenceMs.asDuration(),
            modifier = Modifier.weight(1f),
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // The ledger has counted these since the first reply and nothing showed them.
        // Reading is the other half of the work: a long conversation spends most of its
        // time here, and it is why replies get slower as a chat grows.
        Stat(
            label = "Tokens read",
            value = summary.lifetimePromptTokens.grouped(),
            modifier = Modifier.weight(1f),
        )
        Stat(
            label = "Tokens written",
            value = summary.lifetimeGeneratedTokens.grouped(),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What today added, and how that compares with yesterday.
 *
 * The lifetime figure above it only ever grows, so on its own it stops meaning anything
 * after the first week. This is the part that changes.
 */
@Composable
private fun DayChange(summary: UsageSummary) {
    if (summary.growth.isEmpty()) return
    val dark = LocalIsDarkTheme.current
    val change = summary.dayOverDayChange

    val comparison = when {
        summary.tokensToday == 0L -> "nothing yet today"
        change == null -> "nothing yesterday to compare with"
        change > 0 -> "${change.asPercent()} more than yesterday"
        change < 0 -> "${(-change).asPercent()} less than yesterday"
        else -> "the same as yesterday"
    }

    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "+${summary.tokensToday.grouped()} today",
            style = MaterialTheme.typography.labelLarge,
            // Coloured against yesterday, which is the only thing this line claims. A
            // quiet day is grey, not red: not using your phone is not a fault.
            color = signalColor(fraction = changeFraction(change), dark = dark),
        )
        Metric(comparison)
    }
}

/**
 * A day-over-day change mapped onto the signal scale.
 *
 * Half is the middle of the scale and means unchanged. A day that doubles reaches the top;
 * anything past that has nowhere further to go, which is fine, since the number is right
 * there beside it.
 */
private fun changeFraction(change: Double?): Float {
    if (change == null) return UNCHANGED.toFloat()
    return (UNCHANGED + change / 2).toFloat().coerceIn(0f, 1f)
}

private fun Double.asPercent(): String =
    String.format(Locale.getDefault(), "%.0f%%", this * PERCENT)

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
 * The last seven days, one bar a day, with the day of the week under each.
 *
 * A week rather than the whole history, because the question this answers is how much the
 * phone has been used lately, and a lifetime of two-pixel bars answers it for nobody. The
 * weekday letters are what make it read as a week instead of an anonymous run of numbers,
 * and they are the reason this is bars rather than a line: seven discrete days are seven
 * things, and a line between them implies a reading in the gaps that does not exist.
 *
 * Days with nothing on them are drawn as a floor rather than skipped. A quiet Sunday is
 * part of the shape of a week, and a chart that omits it makes six days look like seven.
 */
@Composable
private fun WeekChart(growth: List<GrowthPoint>) {
    val latest = growth.lastOrNull()?.day ?: return
    // Seven slots always, counted back from the most recent day, rather than however many
    // days happen to have rows. A first-day user has one point, and one bar is not a week:
    // the six empty days are the context that makes the one full day mean something.
    val tokensByDay = growth.associate { it.day to it.dayTokens }
    val week = (DAYS_IN_WEEK - 1 downTo 0).map { back ->
        val day = latest - back
        day to (tokensByDay[day] ?: 0L)
    }
    val dark = LocalIsDarkTheme.current
    val locale = LocalConfiguration.current.locales[0]
    val peak = week.maxOf { it.second }.coerceAtLeast(1)

    Column {
        Text("This week", style = MaterialTheme.typography.titleSmall)
        Metric("tokens a day · best day ${peak.grouped()}")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(WEEK_CHART_HEIGHT.dp)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            week.forEach { (_, tokens) ->
                val fraction = tokens.toFloat() / peak
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction.coerceAtLeast(EMPTY_DAY_BAR))
                        .clip(RoundedCornerShape(Radius.xs))
                        // A day with nothing on it is drawn in the outline colour, not on
                        // the speed scale. The scale runs from slow to fast, and the bottom
                        // of it is red: a day the phone was not used came out looking like
                        // a day something went wrong with it.
                        .background(
                            if (tokens == 0L) {
                                MaterialTheme.colorScheme.outlineVariant
                            } else {
                                signalColor(fraction, dark)
                            },
                        ),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            week.forEach { (day, _) ->
                Text(
                    text = LocalDate.ofEpochDay(day)
                        .dayOfWeek
                        .getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ModelBreakdown(models: List<ModelUsage>) {
    if (models.isEmpty()) return
    val locale = LocalConfiguration.current.locales[0]
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
                            text = String.format(locale, "· %.1f tok/s", rate),
                            color = signalColor((rate / FAST_TOKENS_PER_SECOND).toFloat(), dark),
                        )
                    }
                }
            }
        }
    }
}

/** Seven bars: a week is the unit people actually think in. */
private const val DAYS_IN_WEEK = 7

/** Tall enough to compare two quiet days, short enough to leave the totals above visible. */
private const val WEEK_CHART_HEIGHT = 96

/** A day with nothing on it still gets a sliver, so the week reads as seven days. */
private const val EMPTY_DAY_BAR = 0.02f

/** The middle of the signal scale, which is where "no change since yesterday" belongs. */
private const val UNCHANGED = 0.5

private const val PERCENT = 100
private const val MILLIS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60

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

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
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
                growth = previewGrowth(),
                perModel = listOf(
                    ModelUsage("LFM2.5-2.6B-Q4_K_M", 120_400, 180, 16.4),
                    ModelUsage("Qwen3-1.7B-Q4_K_M", 22_480, 34, 28.1),
                ),
            ),
        )
    }
}

private fun LongRange.random(): Long = first + (last - first) / 2

/** A few weeks of climbing totals, with a day off each week. */
private fun previewGrowth(): List<GrowthPoint> {
    var running = 0L
    return (0 until PREVIEW_DAYS).map { index ->
        val tokens = if (index % DAYS_PER_WEEK == PREVIEW_REST_DAY) {
            0L
        } else {
            PREVIEW_BASE_TOKENS + index * PREVIEW_DAILY_GROWTH
        }
        running += tokens
        GrowthPoint(day = PREVIEW_FIRST_DAY + index, dayTokens = tokens, cumulativeTokens = running)
    }
}

private const val PREVIEW_DAYS = 24
private const val DAYS_PER_WEEK = 7
private const val PREVIEW_REST_DAY = 3
private const val PREVIEW_BASE_TOKENS = 2_000L
private const val PREVIEW_DAILY_GROWTH = 400L
private const val PREVIEW_FIRST_DAY = 20_000L

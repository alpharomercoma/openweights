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

package io.github.alpharomercoma.openweights.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import java.util.Locale

/**
 * Where one reply's time went.
 *
 * Three headline figures and then the two phases underneath, which is the order somebody
 * actually reads them in: how long before it started, how much it wrote, how long it took
 * in total, and only then the arithmetic that explains those three. A turn on a phone is
 * two different machines doing two different jobs: a batch pass over everything already
 * written, then one token at a time. A single "24 tok/s" hides whichever of them actually
 * cost the reply its seconds.
 *
 * Both phases are shown as tokens, seconds and a rate rather than a rate alone, because
 * the rate on its own cannot be checked and cannot be compared. Fifteen tokens at 49 tok/s
 * and fifteen hundred at 49 tok/s are the same number and a third of a second against half
 * a minute.
 *
 * Every value here is measured. Nothing on this panel is estimated, and anything that was
 * not measured shows a hyphen rather than a plausible number: a reply written before the
 * durations were stored, one stopped part way, one that prefilled nothing because the cache
 * already held the whole conversation. See [TranscriptEntry.prefillMs].
 *
 * The semantics are left alone deliberately. An earlier version collapsed the whole panel
 * into one spoken sentence, which reads well and takes every number out of the tree that
 * anything else could find them in: a screen reader gained a paragraph and the tests lost
 * the ability to assert that the panel says anything at all. In reading order it is already
 * a sentence, "0.41s, to first token" and "Prefill, 54 tokens, 0.38s, 142 tok/s", so there
 * was nothing to buy.
 */
@Composable
fun TurnStatsPanel(entry: TranscriptEntry, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The three the wait is actually made of. First because they are the ones a person
        // has a feeling about before they open this: it took a while to start, it wrote a
        // lot, it took this long.
        Row(modifier = Modifier.fillMaxWidth()) {
            Headline(
                value = entry.timeToFirstTokenMs.asSeconds(locale),
                label = stringResource(R.string.stat_time_to_first_token),
                modifier = Modifier.weight(1f),
            )
            Headline(
                value = entry.totalTokens()?.toString() ?: NOT_MEASURED,
                label = stringResource(R.string.stat_tokens),
                modifier = Modifier.weight(1f),
            )
            Headline(
                value = entry.totalMillis.asSeconds(locale),
                label = stringResource(R.string.stat_total_time),
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        PhaseRow(
            name = stringResource(R.string.stat_prefill),
            tokens = entry.prefillTokens(),
            millis = entry.prefillMillis(),
            tokensPerSecond = entry.prefillTokensPerSecond,
            locale = locale,
        )
        PhaseRow(
            name = stringResource(R.string.stat_decode),
            tokens = entry.generatedTokens,
            millis = entry.decodeMillis(),
            tokensPerSecond = entry.tokensPerSecond,
            locale = locale,
        )
    }
}

/** One of the three figures across the top: the number large, its name quiet underneath. */
@Composable
private fun Headline(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One phase: what it read or wrote, how long it took, and how fast that was.
 *
 * The name column is a fixed width and the three cells share what is left, so "Prefill"
 * and "Decode" put their numbers in the same three places. Down a column is the only
 * direction these are worth reading in, and ragged columns make that impossible.
 */
@Composable
private fun PhaseRow(
    name: String,
    tokens: Int?,
    millis: Long?,
    tokensPerSecond: Double?,
    locale: Locale,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(NAME_COLUMN),
        )
        Cell(
            text = tokens?.let { stringResource(R.string.stat_tokens_value, it) } ?: NOT_MEASURED,
            modifier = Modifier.weight(TOKENS_WEIGHT),
        )
        Cell(text = millis.asSeconds(locale), modifier = Modifier.weight(1f))
        Cell(
            text = tokensPerSecond
                ?.let { String.format(locale, "%.0f tok/s", it) }
                ?: NOT_MEASURED,
            modifier = Modifier.weight(RATE_WEIGHT),
        )
    }
}

@Composable
private fun Cell(text: String, modifier: Modifier = Modifier) {
    Metric(text = text, modifier = modifier, maxLines = 1)
}

/**
 * Whether there is anything measured to show.
 *
 * A reply still arriving has none of this, and half a measurement is worse than none: a
 * panel of dashes says the app failed to measure rather than that the reply is not
 * finished yet.
 */
val TranscriptEntry.hasStats: Boolean
    get() = role == ChatRole.ASSISTANT && !isStreaming && tokensPerSecond != null

/**
 * Everything this turn's prompt and reply came to.
 *
 * The prompt counted whole, cached tokens included, because that is the size of what the
 * model was looking at. What it cost to get there is the prefill row's business.
 */
internal fun TranscriptEntry.totalTokens(): Int? {
    val generated = generatedTokens
    val prompt = promptTokens
    if (generated == null && prompt == null) return null
    return (generated ?: 0) + (prompt ?: 0)
}

/**
 * The tokens this turn actually had to read, which is not the same as the prompt.
 *
 * [TranscriptEntry.promptTokens] is the whole conversation as tokenized this turn; the KV
 * cache answered [TranscriptEntry.cachedTokens] of it for nothing. Prefill is the
 * remainder, and it is the number the prefill rate was computed against, so pairing the
 * rate with the whole prompt would make the row fail its own arithmetic.
 */
internal fun TranscriptEntry.prefillTokens(): Int? {
    val prompt = promptTokens ?: return null
    return (prompt - (cachedTokens ?: 0)).coerceAtLeast(0)
}

/**
 * How long prefill took, stored where it was stored and derived where it was not.
 *
 * Rows written before the duration had a column can still answer this most of the time,
 * since the rate and the token count imply it. Where they cannot (a full cache hit has a
 * null rate) the answer is null and the panel shows a hyphen, which is the honest report.
 */
internal fun TranscriptEntry.prefillMillis(): Long? = prefillMs ?: derive(
    tokens = prefillTokens(),
    tokensPerSecond = prefillTokensPerSecond,
)

/**
 * How long decode took. Derived from `generated - 1` where it was not stored, matching
 * `GenerationStats.decodeTokensPerSecond`: the first token's time is time to first token,
 * so the decode interval covers the ones after it.
 */
internal fun TranscriptEntry.decodeMillis(): Long? = decodeMs ?: derive(
    tokens = generatedTokens?.minus(1),
    tokensPerSecond = tokensPerSecond,
)

private fun derive(tokens: Int?, tokensPerSecond: Double?): Long? {
    if (tokens == null || tokensPerSecond == null) return null
    if (tokens <= 0 || tokensPerSecond <= 0) return null
    return (tokens * MILLIS_PER_SECOND / tokensPerSecond).toLong()
}

/** Seconds to two decimals, or a dash when the number was never taken. */
private fun Long?.asSeconds(locale: Locale): String =
    this?.let { String.format(locale, "%.2fs", it / MILLIS_PER_SECOND) } ?: NOT_MEASURED

/**
 * What stands in for a number nobody measured.
 *
 * A hyphen rather than "0" or "n/a": zero is a claim about the reply and "n/a" is jargon,
 * while a hyphen in a column of figures reads as an absence at a glance.
 */
private const val NOT_MEASURED = "-"

private const val MILLIS_PER_SECOND = 1000.0

/** Wide enough for the longer of the two phase names at body size. */
private val NAME_COLUMN: Dp = 64.dp

/** "1,024 tokens" is the widest cell; "49 tok/s" the narrowest. */
private const val TOKENS_WEIGHT = 1.3f
private const val RATE_WEIGHT = 1.1f

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun TurnStatsPanelPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        TurnStatsPanel(
            entry = TranscriptEntry(
                id = 1,
                role = ChatRole.ASSISTANT,
                text = "An answer",
                tokensPerSecond = 25.1,
                prefillTokensPerSecond = 49.4,
                timeToFirstTokenMs = 412,
                generatedTokens = 96,
                promptTokens = 15,
                cachedTokens = 0,
                prefillMs = 304,
                decodeMs = 3826,
                totalMillis = 4310,
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

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

package io.github.alpharomercoma.openweights.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageSummaryTest {
    @Test
    fun `each speed is total tokens over total time, not a mean of means`() {
        // A slow 1000-token reply and a fast 10-token one must not average to "fast".
        // Weighting by work done is the only figure that describes the device.
        val summary = UsageSummary(
            lifetimeDecodeTokens = 1010,
            lifetimeDecodeMs = 101_000,
            lifetimePrefillTokens = 2020,
            lifetimePrefillMs = 101_000,
        )

        assertThat(summary.decodeTokensPerSecond).isWithin(0.01).of(10.0)
        assertThat(summary.prefillTokensPerSecond).isWithin(0.01).of(20.0)
    }

    @Test
    fun `speed is unknown before anything has been measured with the split`() {
        assertThat(UsageSummary().decodeTokensPerSecond).isNull()
        assertThat(UsageSummary().prefillTokensPerSecond).isNull()
    }

    @Test
    fun `a ledger with no timing does not report an infinite rate`() {
        // The rows a device wrote before the split columns existed hold zero in all four,
        // so they drop out of the rate rather than dividing by nothing.
        val summary = UsageSummary(
            lifetimeDecodeTokens = 500,
            lifetimeDecodeMs = 0,
            lifetimePrefillTokens = 500,
            lifetimePrefillMs = 0,
        )

        assertThat(summary.decodeTokensPerSecond).isNull()
        assertThat(summary.prefillTokensPerSecond).isNull()
    }

    @Test
    fun `the growth curve fills the days nothing happened`() {
        // Two days of use a week apart. Plotted as they come they would sit side by side
        // and the chart would claim the week never passed.
        val days = listOf(DailyUsage(day = 100, generatedTokens = 500), DailyUsage(107, 300))

        val curve = days.growth(today = 107)

        assertThat(curve).hasSize(8)
        assertThat(curve.map { it.day }).isEqualTo((100L..107L).toList())
        assertThat(curve.map { it.dayTokens })
            .containsExactly(500L, 0L, 0L, 0L, 0L, 0L, 0L, 300L).inOrder()
    }

    @Test
    fun `the curve only ever climbs`() {
        val days = listOf(DailyUsage(1, 100), DailyUsage(2, 0), DailyUsage(3, 50))

        val curve = days.growth(today = 3)

        assertThat(curve.map { it.cumulativeTokens }).containsExactly(100L, 100L, 150L).inOrder()
        assertThat(curve.zipWithNext().all { (a, b) -> b.cumulativeTokens >= a.cumulativeTokens })
            .isTrue()
    }

    @Test
    fun `the curve runs to today, not to the last day used`() {
        // Otherwise a fortnight away looks like a chart that stopped rather than a flat
        // line, and the dashboard reads as broken.
        val days = listOf(DailyUsage(day = 10, generatedTokens = 900))

        val curve = days.growth(today = 20)

        assertThat(curve.last().day).isEqualTo(20)
        assertThat(curve.last().dayTokens).isEqualTo(0)
        assertThat(curve.last().cumulativeTokens).isEqualTo(900)
    }

    @Test
    fun `history before the window is carried into the first point`() {
        // Starting the visible curve from zero would draw a device's second month as
        // though it were its first.
        val days = listOf(DailyUsage(1, 10_000), DailyUsage(40, 500), DailyUsage(41, 500))

        val curve = days.growth(today = 41, windowDays = 3)

        assertThat(curve).hasSize(3)
        assertThat(curve.first().cumulativeTokens).isEqualTo(10_000)
        assertThat(curve.last().cumulativeTokens).isEqualTo(11_000)
    }

    @Test
    fun `an unused device has no curve`() {
        assertThat(emptyList<DailyUsage>().growth(today = 100)).isEmpty()
    }

    @Test
    fun `today against yesterday is a fraction of yesterday`() {
        val summary = UsageSummary(
            growth = listOf(
                GrowthPoint(day = 1, dayTokens = 200, cumulativeTokens = 200),
                GrowthPoint(day = 2, dayTokens = 300, cumulativeTokens = 500),
            ),
        )

        assertThat(summary.tokensToday).isEqualTo(300)
        assertThat(summary.tokensYesterday).isEqualTo(200)
        assertThat(summary.dayOverDayChange).isWithin(0.001).of(0.5)
    }

    @Test
    fun `there is no comparison to make against a day with nothing in it`() {
        val summary = UsageSummary(
            growth = listOf(
                GrowthPoint(day = 1, dayTokens = 0, cumulativeTokens = 0),
                GrowthPoint(day = 2, dayTokens = 300, cumulativeTokens = 300),
            ),
        )

        assertThat(summary.dayOverDayChange).isNull()
    }
}

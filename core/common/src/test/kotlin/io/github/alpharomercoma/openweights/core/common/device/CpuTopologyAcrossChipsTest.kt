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

package io.github.alpharomercoma.openweights.core.common.device

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The thread count is decided by frequencies, not by a chip anybody recognised.
 *
 * Two measurements shaped this rule and both were taken on phones this project happens to
 * own: a Snapdragon 8 Gen 3 and a Dimensity MT6991. That is a reasonable way to find a rule
 * and a poor way to trust one, because the failure it guards against, handing a batch to
 * cores twice as slow as the rest, is a property of a layout rather than of a vendor.
 *
 * So this walks the layouts the Android market actually ships, taken from each chip's
 * published core configuration, and checks the rule is sensible on all of them. No chip is
 * named anywhere in the code; these are here so that a phone this project has never seen is
 * covered by something other than optimism.
 */
class CpuTopologyAcrossChipsTest {
    private fun cores(vararg clusters: Pair<Int, Long>): List<Long> =
        clusters.flatMap { (count, khz) -> List(count) { khz } }

    @Test
    fun `every shipping layout picks the fast cores and never a minority of them`() {
        data class Chip(val name: String, val cores: List<Long>, val expected: Int)

        val chips = listOf(
            // Qualcomm
            Chip("Snapdragon 8 Gen 3", cores(1 to 3_300_000, 5 to 3_000_000, 2 to 2_270_000), 6),
            Chip("Snapdragon 8 Elite", cores(2 to 4_320_000, 6 to 3_530_000), 8),
            Chip("Snapdragon 7 Gen 3", cores(1 to 2_630_000, 3 to 2_400_000, 4 to 1_800_000), 4),
            Chip("Snapdragon 695", cores(2 to 2_200_000, 6 to 1_700_000), 8),
            // MediaTek
            Chip("Dimensity 9400 (MT6991)", cores(8 to 3_620_000), 8),
            Chip("Dimensity 9300", cores(4 to 3_250_000, 4 to 2_000_000), 4),
            Chip("Dimensity 8300", cores(1 to 3_350_000, 3 to 3_200_000, 4 to 2_200_000), 4),
            Chip("Helio G99", cores(2 to 2_200_000, 6 to 2_000_000), 8),
            // Google
            Chip("Tensor G4", cores(1 to 3_100_000, 3 to 2_600_000, 4 to 1_950_000), 4),
            Chip("Tensor G3", cores(1 to 2_910_000, 4 to 2_370_000, 4 to 1_700_000), 5),
            // Samsung and others
            Chip("Exynos 2400", cores(1 to 3_200_000, 5 to 2_900_000, 4 to 1_950_000), 6),
            Chip("Unisoc T612", cores(2 to 1_820_000, 6 to 1_800_000), 8),
        )

        chips.forEach { chip ->
            val picked = CpuTopology.performanceCores(chip.cores)

            assertThat(picked).isEqualTo(chip.expected)
            // The two properties that matter whatever the layout: never zero, never more
            // than the phone has, and never a minority of the cores available.
            assertThat(picked).isAtLeast(1)
            assertThat(picked).isAtMost(chip.cores.size)
            assertThat(picked * 2).isAtLeast(chip.cores.size)
        }
    }

    @Test
    fun `a chip with one speed keeps every core`() {
        listOf(4, 6, 8, 10).forEach { count ->
            assertThat(CpuTopology.performanceCores(List(count) { 2_000_000L }))
                .isEqualTo(count)
        }
    }

    @Test
    fun `a reading nobody would trust falls back rather than guessing`() {
        // Null is a core that would not say what its ceiling is, and a phone small enough
        // that splitting it could only hurt.
        assertThat(CpuTopology.performanceCores(null)).isEqualTo(CpuTopology.allCores)
        assertThat(CpuTopology.performanceCores(listOf(2_000_000L)))
            .isEqualTo(CpuTopology.allCores)
    }

    @Test
    fun `a lone fast core never strands the phone on one thread`() {
        // 1 + 7 is the shape where naively taking the fast cluster would leave one thread
        // doing everything, which is slower than the whole chip by a long way.
        assertThat(CpuTopology.performanceCores(cores(1 to 3_000_000, 7 to 1_800_000)))
            .isEqualTo(8)
    }
}

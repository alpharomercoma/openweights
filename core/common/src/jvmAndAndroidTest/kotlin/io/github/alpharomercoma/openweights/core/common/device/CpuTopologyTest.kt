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
 * The cluster rule, over the frequency tables of real chips.
 *
 * Each case is a phone that has been measured or that the rule has to not break, rather
 * than a shape invented to exercise a branch.
 */
class CpuTopologyTest {

    @Test
    fun `drops the little cluster on a Snapdragon 8 Gen 3`() {
        // SM8650: one X4 at 3.19 GHz, five A720 at 2.96, two A520 at 2.27. Six threads
        // measured 139.0 t/s of prompt processing against 105.2 at eight.
        val sm8650 = List(2) { LITTLE_A520 } + List(5) { BIG_A720 } + listOf(PRIME_X4)
        assertThat(CpuTopology.performanceCores(sm8650)).isEqualTo(6)
    }

    @Test
    fun `keeps every core when they all run at one speed`() {
        // A Dimensity MT6991 reports one maximum for all eight, and eight threads is what
        // measured fastest there. Nothing to drop, and the old behaviour is preserved.
        val allBig = List(8) { 3_000_000L }
        assertThat(CpuTopology.performanceCores(allBig)).isEqualTo(8)
    }

    @Test
    fun `drops the little half of a four and four phone`() {
        // The commonest Android shape, and the case that decides where the threshold goes.
        // Four big cores can carry a prompt; leaving four A5xx in the barrier is what the
        // eight thread reading on the SM8650 measured the cost of.
        val fourAndFour = List(4) { SLOW } + List(4) { FAST }
        assertThat(CpuTopology.performanceCores(fourAndFour)).isEqualTo(4)
    }

    @Test
    fun `keeps every core when the slow cluster is made of big cores`() {
        // The Dimensity 9400: four A720s at 2.4 GHz under three X4s and an X925. By
        // frequency the A720s are the slow cluster; by part they are big cores doing two
        // thirds of an X4's work, and dropping them cost a quarter of the prompt speed.
        val dimensity = List(4) { 2_400_000L } + List(3) { 3_300_000L } + listOf(3_730_000L)
        val parts = List(4) { A720 } + List(3) { X4 } + listOf(X925)
        assertThat(CpuTopology.performanceCores(dimensity, parts)).isEqualTo(8)
    }

    @Test
    fun `drops the cores that are little by part, not by frequency`() {
        // The Snapdragon 8 Gen 3 again, now with its parts: the two A520s go.
        val frequencies = listOf(3_300_000L) + List(5) { 3_000_000L } + List(2) { 2_270_000L }
        val parts = listOf(X4) + List(5) { A720 } + List(2) { A520 }
        assertThat(CpuTopology.performanceCores(frequencies, parts)).isEqualTo(6)
    }

    @Test
    fun `keeps all but two when the fast cluster is a minority`() {
        // Two fast cores cannot carry two thirds of a chip's worth of work on their own,
        // so the other cluster keeps its place in the barrier. Not every core of it: on
        // the Snapdragon 8 Elite, which is this shape, all eight prefilled at a quarter of
        // what six did and a short prompt paid three and a half seconds per call for it.
        val twoBigSixLittle = List(6) { SLOW } + List(2) { FAST }
        assertThat(CpuTopology.performanceCores(twoBigSixLittle)).isEqualTo(6)
    }

    @Test
    fun `drops only the slowest of three clusters`() {
        // One prime, four big, four little, which is a Tensor. Five survive; the middle
        // cluster stays because no measurement here says otherwise.
        val threeClusters = List(4) { SLOW } + List(4) { FAST } + listOf(PRIME_X4)
        assertThat(CpuTopology.performanceCores(threeClusters)).isEqualTo(5)
    }

    @Test
    fun `falls back to every core when the kernel will not say`() {
        assertThat(CpuTopology.performanceCores(null)).isEqualTo(CpuTopology.allCores)
    }

    @Test
    fun `leaves a small phone alone`() {
        // Below four cores there is no cluster worth the name and every one is needed.
        assertThat(CpuTopology.performanceCores(List(2) { SLOW } + listOf(FAST)))
            .isEqualTo(CpuTopology.allCores)
    }

    private companion object {
        /** The real ceilings an SM8650 reports, in kHz. */
        const val LITTLE_A520 = 2_265_600L
        const val BIG_A720 = 2_956_800L
        const val PRIME_X4 = 3_187_200L

        /** Two speeds, for the shapes where only the split matters. */
        const val SLOW = 1_800_000L
        const val A520 = 0xd80
        const val A720 = 0xd81
        const val X4 = 0xd82
        const val X925 = 0xd85
        const val FAST = 2_800_000L
    }

    @Test
    fun `reads this machine without throwing and answers something usable`() {
        assertThat(CpuTopology.performanceCores()).isAtLeast(1)
        assertThat(CpuTopology.performanceCores()).isAtMost(CpuTopology.allCores)
    }
}

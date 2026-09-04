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

package io.github.alpharomercoma.openweights.core.device

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The speed a phone can be told about before it has run anything.
 *
 * The pair `FitEstimator` needs used to come only from a model this device had already
 * run, so a fresh install could be told a model fits and never that it would be unusable.
 * These pin the arithmetic that fills that gap, against the numbers it was fitted to.
 */
class MemoryBandwidthTest {

    @Test
    fun `lands just under what the dev phone actually decodes`() {
        // What the probe reads on the MT6991, against the 30 tokens a second the engine
        // measured on the 663 MB model it was fitted to.
        val seeded = MemoryBandwidth.calibrationFor(27_300_000_000L)

        val predicted = seeded?.predictFor(663L * 1024 * 1024)

        assertThat(predicted).isNotNull()
        // Under, and knowingly so: an estimate that flatters the phone never shows the
        // warning it exists for. Pinned as a band so the margin cannot quietly invert.
        assertThat(predicted!!).isGreaterThan(20.0)
        assertThat(predicted).isLessThan(30.0)
    }

    @Test
    fun `a model four times the size is predicted four times slower`() {
        val seeded = requireNotNull(MemoryBandwidth.calibrationFor(27_300_000_000L))

        val small = requireNotNull(seeded.predictFor(1_000_000_000L))
        val large = requireNotNull(seeded.predictFor(4_000_000_000L))

        // The whole reason one measurement is worth having: decode is bandwidth over
        // bytes, so the ratio is the ratio of the files and nothing else.
        assertThat(small / large).isWithin(0.01).of(4.0)
    }

    @Test
    fun `a bandwidth that could not be measured says nothing rather than zero`() {
        assertThat(MemoryBandwidth.calibrationFor(0L)).isNull()
        assertThat(MemoryBandwidth.calibrationFor(-1L)).isNull()
    }
}

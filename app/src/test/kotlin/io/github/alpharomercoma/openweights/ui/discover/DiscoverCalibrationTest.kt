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

package io.github.alpharomercoma.openweights.ui.discover

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.data.ModelUsage
import org.junit.Test
import java.io.File

/**
 * `FitEstimator.estimate()` has taken a `ThroughputCalibration` since it was written, and
 * nothing in the running app ever supplied one: the Discover screen has been showing memory
 * and never the speed line beside it. `matchCalibration` is the piece that was missing,
 * turning what this device's own usage ledger already knows into the one measurement the
 * formula needs.
 */
class DiscoverCalibrationTest {
    @Test
    fun `picks the heaviest-used model that is still installed`() {
        val installed = mapOf(
            "LFM2.5-1.2B-Instruct-QAD-Q4_0" to fakeFile(695_755_488L),
            "granite-4.2-3b-Q4_K_M" to fakeFile(2_244_012_160L),
        )
        val perModel = listOf(
            // Most generated tokens first, the way UsageRepository already sorts it.
            ModelUsage("LFM2.5-1.2B-Instruct-QAD-Q4_0", 50_000, 40, 30.5),
            ModelUsage("granite-4.2-3b-Q4_K_M", 500, 2, 8.2),
        )

        val calibration = matchCalibration(perModel, installed)

        assertThat(calibration?.measuredBytes).isEqualTo(695_755_488L)
        assertThat(calibration?.measuredTokensPerSecond).isEqualTo(30.5)
    }

    @Test
    fun `skips a model whose usage this device remembers but whose file is gone`() {
        val installed = mapOf("granite-4.2-3b-Q4_K_M" to fakeFile(2_244_012_160L))
        val perModel = listOf(
            ModelUsage("a-deleted-model", 50_000, 40, 30.5),
            ModelUsage("granite-4.2-3b-Q4_K_M", 500, 2, 8.2),
        )

        val calibration = matchCalibration(perModel, installed)

        assertThat(calibration?.measuredBytes).isEqualTo(2_244_012_160L)
    }

    @Test
    fun `skips a model with no measured speed yet`() {
        val installed = mapOf(
            "no-speed-yet" to fakeFile(1_000_000L),
            "granite-4.2-3b-Q4_K_M" to fakeFile(2_244_012_160L),
        )
        val perModel = listOf(
            ModelUsage("no-speed-yet", 50_000, 40, averageTokensPerSecond = null),
            ModelUsage("granite-4.2-3b-Q4_K_M", 500, 2, 8.2),
        )

        val calibration = matchCalibration(perModel, installed)

        assertThat(calibration?.measuredBytes).isEqualTo(2_244_012_160L)
    }

    @Test
    fun `null with nothing installed or nothing measured`() {
        assertThat(matchCalibration(emptyList(), emptyMap())).isNull()
    }

    /**
     * The formula itself, checked against this session's own live device measurements
     * rather than invented numbers: LFM2.5-1.2B-Instruct measured at roughly 30 tokens a
     * second on a 695,755,488 byte file, used to predict granite-4.2-3b-Q4_K_M's actual
     * measured speed of 8.2 tokens a second on a 2,244,012,160 byte file. Bandwidth-bound
     * decode does not promise an exact match, only the right order of magnitude from one
     * real point — this checks that promise rather than the arithmetic alone.
     */
    @Test
    fun `predicts within measured range of a real second model on this device`() {
        val installed = mapOf("LFM2.5-1.2B-Instruct-QAD-Q4_0" to fakeFile(695_755_488L))
        val perModel = listOf(ModelUsage("LFM2.5-1.2B-Instruct-QAD-Q4_0", 50_000, 40, 30.0))

        val calibration = matchCalibration(perModel, installed)
        val predicted = calibration?.predictFor(2_244_012_160L)

        // Actually measured on this device: 8.2 tokens a second.
        assertThat(predicted).isNotNull()
        assertThat(predicted!!).isWithin(2.0).of(8.2)
    }

    private fun fakeFile(sizeBytes: Long): File {
        val file = File.createTempFile("calibration-test", ".gguf")
        file.deleteOnExit()
        file.writeBytes(ByteArray(0))
        // RandomAccessFile.setLength avoids actually writing gigabytes to disk for a test
        // that only ever reads File.length().
        java.io.RandomAccessFile(file, "rw").use { it.setLength(sizeBytes) }
        return file
    }
}

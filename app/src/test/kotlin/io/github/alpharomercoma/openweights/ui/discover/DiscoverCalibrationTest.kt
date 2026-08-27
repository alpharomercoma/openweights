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
import io.github.alpharomercoma.openweights.core.data.db.ModelDecodeSpeed
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
            "granite-4.2-3b-Q2_K" to fakeFile(1_455_736_960L),
        )
        val decodeSpeeds = listOf(
            // Most generated tokens first, the way UsageDao.decodeSpeedByModel already sorts it.
            ModelDecodeSpeed("LFM2.5-1.2B-Instruct-QAD-Q4_0", 32.46, 2519),
            ModelDecodeSpeed("granite-4.2-3b-Q2_K", 6.73, 2048),
        )

        val calibration = matchCalibration(decodeSpeeds, installed)

        assertThat(calibration?.measuredBytes).isEqualTo(695_755_488L)
        assertThat(calibration?.measuredTokensPerSecond).isEqualTo(32.46)
    }

    @Test
    fun `skips a model whose usage this device remembers but whose file is gone`() {
        val installed = mapOf("granite-4.2-3b-Q2_K" to fakeFile(1_455_736_960L))
        val decodeSpeeds = listOf(
            ModelDecodeSpeed("a-deleted-model", 32.46, 2519),
            ModelDecodeSpeed("granite-4.2-3b-Q2_K", 6.73, 2048),
        )

        val calibration = matchCalibration(decodeSpeeds, installed)

        assertThat(calibration?.measuredBytes).isEqualTo(1_455_736_960L)
    }

    @Test
    fun `null with nothing installed`() {
        val decodeSpeeds = listOf(ModelDecodeSpeed("qwen", 32.46, 2519))
        assertThat(matchCalibration(decodeSpeeds, emptyMap())).isNull()
    }

    @Test
    fun `null with nothing measured yet, such as a fresh install`() {
        assertThat(matchCalibration(emptyList(), mapOf("qwen" to fakeFile(1_000_000L)))).isNull()
    }

    /**
     * The arithmetic alone, pinned tightly: 32.46 tokens a second measured on a 695,755,488
     * byte file predicts exactly 32.46 × 695,755,488 ÷ 1,455,736,960 for a file that size,
     * and nothing else. Whether that prediction matches reality is a different question,
     * answered by the next test — a loose tolerance here would let a wrong formula pass by
     * accident, which is the one thing a test of the formula itself must not do.
     */
    @Test
    fun `predicts by simple inverse proportion to file size`() {
        val installed = mapOf("LFM2.5-1.2B-Instruct-QAD-Q4_0" to fakeFile(695_755_488L))
        val decodeSpeeds = listOf(ModelDecodeSpeed("LFM2.5-1.2B-Instruct-QAD-Q4_0", 32.46, 2519))

        val calibration = matchCalibration(decodeSpeeds, installed)
        val predicted = calibration?.predictFor(1_455_736_960L)

        assertThat(predicted).isNotNull()
        assertThat(predicted!!).isWithin(0.01).of(32.46 * 695_755_488.0 / 1_455_736_960.0)
    }

    /**
     * The formula's real accuracy, not an invented number: this session measured
     * LFM2.5-1.2B-Instruct at a genuine, decode-only 32.46 tokens a second
     * (695,755,488 bytes) and granite-4.2-3b-Q2_K, a different architecture entirely, at a
     * genuine decode-only 6.73 (1,455,736,960 bytes). One predicts the other at ≈15.5, over
     * double the real figure — documented here as a fact about the formula's real-world
     * accuracy across architectures, not the arithmetic, which the previous test already
     * pins tightly.
     *
     * codex and agy's research (independent, both citing real llama.cpp measurements) found
     * the bandwidth-bound formula holds tightly within one architecture and quant family,
     * and can be off by multiples of that across architectures, across MoE-vs-dense, and
     * especially across K-quant-vs-IQ-quant, where a smaller IQ file has sometimes measured
     * *slower* than a larger K-quant one because its dequantization is compute-bound rather
     * than bandwidth-bound. This assertion only checks that the prediction lands clearly
     * outside a tight band around the true value — proving the gap is real without claiming
     * a precision the formula does not have — so a future correction (per-architecture or
     * per-quant-family calibration) has a real number to close rather than a synthetic one
     * that would have hidden it.
     */
    @Test
    fun `a cross-architecture prediction is real-world off by more than double`() {
        val installed = mapOf("LFM2.5-1.2B-Instruct-QAD-Q4_0" to fakeFile(695_755_488L))
        val decodeSpeeds = listOf(ModelDecodeSpeed("LFM2.5-1.2B-Instruct-QAD-Q4_0", 32.46, 2519))

        val calibration = matchCalibration(decodeSpeeds, installed)
        val predicted = calibration?.predictFor(1_455_736_960L)

        // Actually measured on this device: 6.73 tokens a second.
        assertThat(predicted).isNotNull()
        assertThat(predicted!!).isGreaterThan(6.73 * 2)
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

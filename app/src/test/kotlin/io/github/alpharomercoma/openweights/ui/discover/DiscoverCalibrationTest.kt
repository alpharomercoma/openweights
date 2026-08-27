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
     * Two real, single-run residuals, not an invented number: this session measured
     * LFM2.5-1.2B-Instruct at a genuine, decode-only 32.46 tokens a second
     * (695,755,488 bytes), then granite-4.2-3b-Q2_K at a genuine decode-only 6.73
     * (1,455,736,960 bytes) and, later the same session, Qwen3-1.7B-Q4_K_M at a genuine
     * decode-only 16.54 (1,107,409,472 bytes). Calibrated from LFM, the formula predicts
     * ≈15.5 for granite (real: 6.73, prediction ~2.3x too optimistic) and ≈20.4 for Qwen3
     * (real: 16.54, prediction ~1.23x too optimistic).
     *
     * **What this is not:** a characterisation of the formula's error profile. codex and
     * agy both reviewed this exact data and independently gave the same verdict: two
     * out-of-sample residuals, each a single run of 178-2519 tokens with no repeats, is
     * not enough to say the error "scales with architectural distance" — that reads a
     * trend into what could just as easily be measurement noise (thermal state, background
     * load, sample size all uncontrolled and unmeasured here). Both explicitly warned
     * against shipping a UI claim like "give or take ~2x": the observed error already
     * exceeds 2x for granite, and nothing here bounds it from above.
     *
     * These two assertions are pinned regression fixtures — real numbers this device
     * produced, worth re-checking if the formula changes — not proof the estimator is
     * reliable across architectures. A real accuracy claim needs repeated, controlled runs
     * (fixed token count, fixed context depth, randomised order, reported dispersion)
     * across a deliberately stratified model/quant/backend panel, per codex and agy's
     * shared recommendation, which this session's on-device testing time did not cover.
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

    @Test
    fun `a same-family dense-transformer prediction is real-world off by a smaller margin`() {
        val installed = mapOf("LFM2.5-1.2B-Instruct-QAD-Q4_0" to fakeFile(695_755_488L))
        val decodeSpeeds = listOf(ModelDecodeSpeed("LFM2.5-1.2B-Instruct-QAD-Q4_0", 32.46, 2519))

        val calibration = matchCalibration(decodeSpeeds, installed)
        val predicted = calibration?.predictFor(1_107_409_472L)

        // Actually measured on this device: 16.54 tokens a second (249-token sample).
        assertThat(predicted).isNotNull()
        assertThat(predicted!!).isGreaterThan(16.54 * 1.1)
        assertThat(predicted).isLessThan(16.54 * 1.4)
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

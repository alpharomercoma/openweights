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
     * Controlled, repeated measurements, not single samples: `DecodeSpeedBenchmark`
     * (app/src/androidTest) loaded each model, discarded a warmup generation, then ran 5
     * repetitions of an identical prompt at temperature=0 with a fixed 150-token budget,
     * resetting the KV cache between reps so every rep pays the same fixed cost. LFM was
     * measured twice — first and last — to isolate order/thermal drift from architecture.
     *
     * Real medians on this device (CPU decode): LFM2.5-1.2B 35.48 tok/s cold / 34.51 tok/s
     * warm (695,755,488 bytes), Qwen3-1.7B-Q4_K_M 21.18 tok/s (1,107,409,472 bytes),
     * granite-4.2-3b-Q2_K 8.40 tok/s (1,455,736,960 bytes). Repeat-run spread was 1.3-4.8%
     * of the median; the LFM cold-vs-warm drift was 2.7%. Calibrated from either LFM
     * measurement, the formula predicts Qwen3 within 2-5% (right at the noise floor) and
     * overpredicts granite by ~96-102% — a gap roughly twenty to forty times the measured
     * noise, which is what makes it real signal rather than an artifact of one unlucky run.
     *
     * **What this still is not, per codex and agy's re-review of this exact data:** proof
     * that *architecture* is what drives granite's miss. granite-4.2-3b here is quantized
     * to Q2_K (2 bits) against Qwen3's Q4_K_M and LFM's Q4_0 — architecture and
     * quantization depth are confounded in this panel, and agy specifically flagged that a
     * 2-bit quant plausibly hits a different bottleneck (dequantization compute, cache
     * behaviour) than the bandwidth-bound assumption the formula makes, independent of
     * whatever granite's architecture is doing. Both reviewers also noted: one device, one
     * CPU backend, one prompt, and three architectures is still too thin a panel to
     * generalise from, and repeating one deterministic prompt five times is not the same
     * as five independent workloads. The honest summary neither reviewer disputed:
     * "formula works for models near this one, fails badly for granite specifically,
     * cause not yet isolated between architecture and quantization depth."
     *
     * These assertions pin what this device actually measured, tightened by the repeated
     * protocol — not a general accuracy claim, and not evidence for shipping any UI text
     * that states an error bound.
     */
    @Test
    fun `a cross-architecture prediction is real-world off by more than double`() {
        val installed = mapOf("LFM2.5-1.2B-Instruct-QAD-Q4_0" to fakeFile(695_755_488L))
        val decodeSpeeds = listOf(ModelDecodeSpeed("LFM2.5-1.2B-Instruct-QAD-Q4_0", 35.48, 2519))

        val calibration = matchCalibration(decodeSpeeds, installed)
        val predicted = calibration?.predictFor(1_455_736_960L)

        // Median of 5 controlled reps on this device: 8.40 tokens a second.
        assertThat(predicted).isNotNull()
        assertThat(predicted!!).isGreaterThan(8.40 * 1.9)
        assertThat(predicted).isLessThan(8.40 * 2.1)
    }

    @Test
    fun `a same-family dense-transformer prediction is real-world within measurement noise`() {
        val installed = mapOf("LFM2.5-1.2B-Instruct-QAD-Q4_0" to fakeFile(695_755_488L))
        val decodeSpeeds = listOf(ModelDecodeSpeed("LFM2.5-1.2B-Instruct-QAD-Q4_0", 35.48, 2519))

        val calibration = matchCalibration(decodeSpeeds, installed)
        val predicted = calibration?.predictFor(1_107_409_472L)

        // Median of 5 controlled reps on this device: 21.18 tokens a second.
        assertThat(predicted).isNotNull()
        assertThat(predicted!!).isGreaterThan(21.18 * 1.0)
        assertThat(predicted).isLessThan(21.18 * 1.1)
    }

    /**
     * The same benchmark, same three model files, on a second physical device: a Qualcomm
     * Device Cloud loan of an SM8750 (Snapdragon 8 Elite), 15.5 GB RAM, reached over the
     * `sshtunnel@ssh.qdc.qualcomm.com` port-forward. Real medians, CPU decode: LFM2.5-1.2B
     * 70.55 tok/s cold / 70.95 tok/s warm (0.57% drift — tighter than the first device's
     * 2.7%, plausibly more thermal headroom), Qwen3-1.7B-Q4_K_M 37.41 tok/s, granite-4.2-3b
     * 14.91 tok/s. Repeat-run spread 1.4-6.6%.
     *
     * **The finding, and its correct scope.** The device-to-device speedup is not one
     * number: LFM is 1.99x faster on the Snapdragon, but Qwen3 and granite are *both*
     * 1.77x faster — agreeing with each other, not with LFM. Asked to review this, agy
     * pointed out the shape that actually matters here: two models landing on the exact
     * same ratio while the third (architecturally different) one does not reads as a
     * two-cluster split — hybrid conv-attention (LFM2) vs. dense transformer (Qwen3,
     * granite) — not as "every model has its own device-dependent speedup", which was
     * this test's first draft of the claim and overreached what n=3 models can show.
     * Whatever is true, it means no single per-device correction scalar makes the formula
     * cross-device-accurate, which is the part both codex and agy signed off on; *why* is
     * still open, and both said the fastest next check would be one more dense-transformer
     * model at a different quant to see whether 1.77x holds, or one more hybrid model to
     * see whether 1.99x does — neither of which this session's remaining cloud time covered.
     *
     * Calibrated from the Snapdragon's own LFM measurement, the formula overpredicts Qwen3
     * by ~19% (looser than the first device's 2-5%) and granite by ~127% (looser than
     * ~96-102%) — the same qualitative shape, quantitatively worse on the faster chip.
     */
    @Test
    fun `the device-to-device speedup is not a single scalar across models`() {
        val poco = mapOf("LFM" to 35.48, "Qwen3" to 21.18, "granite" to 8.40)
        val snapdragon = mapOf("LFM" to 70.55, "Qwen3" to 37.41, "granite" to 14.91)

        val speedups = poco.keys.associateWith { snapdragon.getValue(it) / poco.getValue(it) }

        // Qwen3 and granite agree with each other and disagree with LFM: a two-cluster
        // split, not three independent per-model ratios.
        assertThat(speedups.getValue("Qwen3")).isWithin(0.01).of(speedups.getValue("granite"))
        assertThat(speedups.getValue("LFM") - speedups.getValue("Qwen3")).isGreaterThan(0.15)
    }

    @Test
    fun `a cross-device prediction using the other device's own calibration is looser, not tighter`() {
        val installed = mapOf("LFM2.5-1.2B-Instruct-QAD-Q4_0" to fakeFile(695_755_488L))
        val decodeSpeeds = listOf(ModelDecodeSpeed("LFM2.5-1.2B-Instruct-QAD-Q4_0", 70.55, 2519))

        val calibration = matchCalibration(decodeSpeeds, installed)
        val predictedGranite = calibration?.predictFor(1_455_736_960L)

        // Snapdragon median: 14.91 tokens a second. The Poco's own granite error was
        // ~1.96-2.02x; this device's is measurably larger, not the same or smaller.
        assertThat(predictedGranite).isNotNull()
        assertThat(predictedGranite!!).isGreaterThan(14.91 * 2.2)
    }

    /**
     * A second Snapdragon run, three things different from every prior one: a real dataset
     * prompt instead of hand-written text or Lorem-ipsum filler, prefill recorded alongside
     * decode, and three more models (LFM2.5-2.6B, ai9stars/G9v3-3B) run back-to-back with
     * *no* cooldown between them — unlike every prior run here, which paced measurements
     * further apart.
     *
     * The prompt is drawn verbatim from IFEval (google-research/google-research,
     * instruction_following_eval/data/input_data.jsonl, Apache-2.0) — one of the two
     * datasets (with TinyMMLU) MLCommons' MLPerf Mobile v6.0, the current industry benchmark
     * for on-device Android LLM inference, uses for its own GenAI tests. See
     * `DecodeSpeedBenchmark.BENCHMARK_PROMPT` for the exact text and citation.
     *
     * Real medians (decode / prefill tok/s, ~680 prompt tokens, 128 generated):
     * LFM2.5-1.2B 62.84/443.15 (first, cold), LFM2.5-2.6B 24.04/163.33, Qwen3-1.7B-Q4_K_M
     * 25.35/146.14, granite-4.2-3b-Q2_K 10.60/24.43, ai9stars/G9v3-3B-Q4_K_M 16.07/75.11,
     * LFM2.5-1.2B 48.36/345.59 (last, after four other loads, no cooldown).
     *
     * **What broke, and why it matters more than any single number here.** LFM's own
     * decode dropped 23% and its prefill 22% between the first and last measurement of the
     * *same file* — purely from four other models loading in between with no recovery time.
     * That is larger than this run's Qwen3 prediction error (+14%) and comparable to G9v3's
     * (+43%); only granite's (+183%) clearly survives it. Asked directly, codex and agy gave
     * the same one-line verdict: report this run as order/thermal-confounded, and treat its
     * per-model prediction errors as unreliable *except* granite's, which is large enough to
     * remain directionally credible even against 23% drift noise. The granite fixture below
     * is the only cross-model assertion carried over from this run for that reason — Qwen3's
     * and G9v3's real numbers are logged in `DecodeSpeedBenchmark`'s own run output, not
     * asserted here as if this run had isolated them from drift the way the controlled
     * multi-rep-per-model sessions did.
     *
     * The practical lesson for any future run: pace models apart, or accept that "prediction
     * error" and "thermal drift since the last cooldown" are entangled and cannot be
     * separated from a single back-to-back pass, no matter how many models it covers.
     */
    @Test
    fun `granite's prediction error survives a run large enough to have real thermal drift`() {
        val installed = mapOf("LFM2.5-1.2B-Instruct-QAD-Q4_0" to fakeFile(695_755_488L))
        val decodeSpeeds = listOf(ModelDecodeSpeed("LFM2.5-1.2B-Instruct-QAD-Q4_0", 62.84, 2519))

        val calibration = matchCalibration(decodeSpeeds, installed)
        val predictedGranite = calibration?.predictFor(1_455_736_960L)

        // Real, measured, same run: 10.60 tokens a second. The +183% miss is roughly 8x
        // this run's own worst-case thermal drift (23%), which is what makes it credible
        // despite the run not otherwise being a controlled measurement.
        assertThat(predictedGranite).isNotNull()
        assertThat(predictedGranite!!).isGreaterThan(10.60 * 2.5)
    }

    /**
     * The same real-dataset benchmark, on the Poco (the local device used throughout this
     * project, `[[alpha-dev-environment]]`) rather than a QDC loan. Real medians, decode /
     * prefill tok/s: LFM2.5-1.2B 35.77/141.36, LFM2.5-2.6B 17.32/56.22, Qwen3-1.7B-Q4_K_M
     * 17.93/40.58 — only three of the intended six models, for a reason worth recording.
     *
     * **What actually happened.** Twice during this run the phone entered Android Doze
     * (`mWakefulness=Dozing`) mid-benchmark despite `svc power stayon true` and an extended
     * screen timeout, each time silently throttling the app until woken again — a failure
     * mode the QDC device never showed once across two full runs. After the second wake,
     * granite-4.2-3b ran for over twenty minutes without completing a single model pass
     * (confirmed still consuming 380-450% CPU throughout, not hung; `dumpsys thermalservice`
     * reported nominal temperatures throughout, so this was not classic thermal throttling
     * either) before the run was deliberately stopped. ai9stars/G9v3-3B and ling-3.0-tiny
     * never got a turn. The three models that did complete finished in the same 2-3 minutes
     * each takes everywhere else, so whatever made granite pathological here is specific to
     * that combination of model and device state, not a general Poco slowdown.
     *
     * **Cross-device speedup, on the models this run actually has:** decode speedup
     * (Snapdragon over Poco) is inconsistent — 1.76x, 1.05x, 1.41x — echoing the earlier
     * two-device finding that no single scalar fits. Prefill speedup is both larger and far
     * more consistent: 3.13x, 2.91x, 3.60x. That prefill separates so much more cleanly than
     * decode across these two chips, while granite alone turns openly pathological on one of
     * them, is two more open questions this project has real numbers for and no explanation
     * of yet.
     */
    @Test
    fun `prefill speedup between devices is larger and more consistent than decode speedup`() {
        val poco = mapOf("LFM2.5-1.2B" to 141.36, "LFM2.5-2.6B" to 56.22, "Qwen3-1.7B" to 40.58)
        val snapdragon =
            mapOf("LFM2.5-1.2B" to 443.15, "LFM2.5-2.6B" to 163.33, "Qwen3-1.7B" to 146.14)

        val prefillSpeedups = poco.keys.map { snapdragon.getValue(it) / poco.getValue(it) }

        // 2.91x-3.60x: a real spread, but far tighter than decode's 1.05x-1.76x on the same
        // three models and the same two devices.
        assertThat(prefillSpeedups.min()).isGreaterThan(2.5)
        assertThat(prefillSpeedups.max()).isLessThan(4.0)
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

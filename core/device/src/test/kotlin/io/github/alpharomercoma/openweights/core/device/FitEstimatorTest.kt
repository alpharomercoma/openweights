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
import io.github.alpharomercoma.openweights.core.common.model.GgufFileType
import io.github.alpharomercoma.openweights.core.common.model.GgufMetadata
import org.junit.Test

private const val GIB = 1024L * 1024 * 1024
private const val MIB = 1024L * 1024

class FitEstimatorTest {
    private val estimator = FitEstimator()

    /** The real dev device: 11 GiB total, plenty of storage. */
    private val phone = DeviceProfile(
        totalMemoryBytes = 11L * GIB,
        availableMemoryBytes = 5L * GIB,
        freeStorageBytes = 300L * GIB,
        cpuCores = 8,
        socModel = "MT6991",
        isLowRamDevice = false,
    )

    /** LFM2.5-2.6B: attention in only 10 of 30 blocks. */
    private val hybrid = GgufMetadata(
        architecture = "lfm2",
        blockCount = 30,
        embeddingLength = 2048,
        headCount = 32,
        keyValueHeadsPerLayer = List(30) { if (it % 3 == 2) 8 else 0 },
        trainingContextLength = 128_000,
        fileType = GgufFileType.Q4_K_M,
        name = "LFM2.5-2.6B",
    )

    @Test
    fun `a small model on a big phone is comfortable`() {
        val report = estimator.estimate(
            phone,
            hybrid,
            fileSizeBytes = 1670 * MIB,
            contextLength = 4096,
        )

        assertThat(report.verdict).isEqualTo(FitVerdict.COMFORTABLE)
    }

    @Test
    fun `hybrid architectures are not charged for blocks that have no attention`() {
        // 10 attending blocks x 8 heads x 64 wide x 4096 tokens x 2 tensors x 2 bytes = 80 MiB.
        // Charging all 30 blocks would claim 240 MiB and could turn a fit into a refusal.
        val report = estimator.estimate(
            phone,
            hybrid,
            fileSizeBytes = 1670 * MIB,
            contextLength = 4096,
        )

        assertThat(report.kvCacheBytes).isEqualTo(80 * MIB)
    }

    @Test
    fun `a model that fits the device but not its free memory is reported as tight`() {
        // The phone could hold it, but right now other apps have the RAM. Calling that
        // comfortable would be a promise the system may break by killing something.
        val busyPhone = phone.copy(availableMemoryBytes = 1L * GIB)

        val report =
            estimator.estimate(busyPhone, hybrid, fileSizeBytes = 1670 * MIB, contextLength = 4096)

        assertThat(report.verdict).isEqualTo(FitVerdict.TIGHT)
    }

    @Test
    fun `a model larger than usable memory will not run`() {
        val report = estimator.estimate(
            phone,
            hybrid,
            fileSizeBytes = 20L * GIB,
            contextLength = 4096,
        )

        assertThat(report.verdict).isEqualTo(FitVerdict.WONT_RUN)
    }

    @Test
    fun `storage is checked before memory, since it fails first and sooner`() {
        val nearlyFull = phone.copy(freeStorageBytes = 1L * GIB)

        val report = estimator.estimate(
            nearlyFull,
            hybrid,
            fileSizeBytes = 4L * GIB,
            contextLength = 4096,
        )

        assertThat(report.verdict).isEqualTo(FitVerdict.NO_ROOM_TO_DOWNLOAD)
    }

    @Test
    fun `a long context can turn a comfortable model tight`() {
        // Memory held generously free so the only variable under test is context length.
        val idlePhone = phone.copy(availableMemoryBytes = 9L * GIB)
        val short = estimator.estimate(
            idlePhone,
            hybrid,
            fileSizeBytes = 5L * GIB,
            contextLength = 2048,
        )
        val long = estimator.estimate(
            idlePhone,
            hybrid,
            fileSizeBytes = 5L * GIB,
            contextLength = 65_536,
        )

        assertThat(short.verdict).isEqualTo(FitVerdict.COMFORTABLE)
        assertThat(long.verdict).isAnyOf(FitVerdict.TIGHT, FitVerdict.WONT_RUN)
        assertThat(long.kvCacheBytes).isGreaterThan(short.kvCacheBytes)
    }

    @Test
    fun `max context never exceeds what the model was trained for`() {
        val shortTrained = hybrid.copy(trainingContextLength = 4096)

        val maximum = estimator.maxContextLength(phone, shortTrained, fileSizeBytes = 1670 * MIB)

        assertThat(maximum).isEqualTo(4096)
    }

    @Test
    fun `a low-RAM device gets a smaller budget`() {
        val cheapPhone = phone.copy(totalMemoryBytes = 3L * GIB, isLowRamDevice = true)

        assertThat(cheapPhone.usableMemoryBytes).isLessThan(phone.usableMemoryBytes)
        val report = estimator.estimate(
            cheapPhone,
            hybrid,
            fileSizeBytes = 1670 * MIB,
            contextLength = 4096,
        )
        assertThat(report.verdict).isEqualTo(FitVerdict.WONT_RUN)
    }

    @Test
    fun `a header claiming a huge window does not open one`() {
        // The header says 128,000 for this model and its own card says 32,768, because
        // `context_length` is how far the positional encoding reaches rather than how far the
        // model was trained. Opening at the header would be opening past what the publisher
        // validated, so the automatic window stays well inside it whatever the file claims.
        val window = estimator.defaultContextLength(phone, hybrid, fileSizeBytes = 1670 * MIB)

        assertThat(window).isAtMost(32_768)
        assertThat(window).isAtLeast(16_384)
    }

    @Test
    fun `a rope-extended model is read at the length it was trained at`() {
        // The one machine-readable correction there is: a model whose window was extended
        // writes the pre-extension length as well, and that is the honest number.
        val extended = hybrid.copy(trainingContextLength = 4_096)

        assertThat(estimator.defaultContextLength(phone, extended, fileSizeBytes = 1670 * MIB))
            .isEqualTo(4_096)
    }

    @Test
    fun `a transformer of half the size gets a much smaller window, and that is the point`() {
        // Attention in every block rather than a third of them. The same phone, a smaller
        // file, and a window an order of magnitude shorter, because what bounds it is bytes
        // per token rather than parameters. A fixed default cannot be right for both of
        // these, which is the whole argument for computing it.
        // Qwen3 1.7B's real shape: 28 blocks, 16 heads over 2048, 8 of them keeping a cache.
        // That is 112 KB a token against the hybrid's 20 KB.
        val transformer = hybrid.copy(
            architecture = "qwen3",
            blockCount = 28,
            headCount = 16,
            keyValueHeadsPerLayer = List(28) { 8 },
            trainingContextLength = 32_768,
        )

        val window = estimator.defaultContextLength(phone, transformer, fileSizeBytes = 1100 * MIB)

        assertThat(window).isLessThan(32_768)
        assertThat(window).isAtLeast(4_096)
    }

    @Test
    fun `a model trained short is never opened wider than it was trained`() {
        // The bug in the other direction, and the one nobody would have noticed: the app
        // asked for 4096 whatever the model said, llama.cpp allowed it, and past its training
        // length the answers quietly get worse.
        val short = hybrid.copy(trainingContextLength = 2_048)

        val window = estimator.defaultContextLength(phone, short, fileSizeBytes = 500 * MIB)

        assertThat(window).isEqualTo(2_048)
    }

    @Test
    fun `a model trained shorter than the floor is opened at what it was trained for`() {
        // coerceIn throws when its minimum is above its maximum, so a model trained to less
        // than the floor took the load down with an IllegalArgumentException rather than
        // opening small. Tiny models do exist and this is the one input nobody would try.
        val tiny = hybrid.copy(trainingContextLength = 1_024)

        assertThat(estimator.defaultContextLength(phone, tiny, fileSizeBytes = 100 * MIB))
            .isEqualTo(1_024)
    }

    @Test
    fun `a header that says nothing about training length falls back rather than guessing`() {
        val silent = hybrid.copy(trainingContextLength = 0)

        assertThat(estimator.defaultContextLength(phone, silent, fileSizeBytes = 500 * MIB))
            .isEqualTo(4_096)
    }

    @Test
    fun `a low-RAM phone gets a shorter window for the same model`() {
        val small = phone.copy(totalMemoryBytes = 4L * GIB, isLowRamDevice = true)

        val onSmall = estimator.defaultContextLength(small, hybrid, fileSizeBytes = 1670 * MIB)
        val onBig = estimator.defaultContextLength(phone, hybrid, fileSizeBytes = 1670 * MIB)

        assertThat(onSmall).isLessThan(onBig)
        assertThat(onSmall).isAtLeast(2_048)
    }

    @Test
    fun `the projector counts, because it is loaded too`() {
        val withEyes = estimator.defaultContextLength(
            phone,
            hybrid,
            fileSizeBytes = 1670 * MIB,
            projectorSizeBytes = 600 * MIB,
        )

        assertThat(withEyes).isAtMost(
            estimator.defaultContextLength(phone, hybrid, fileSizeBytes = 1670 * MIB),
        )
    }

    @Test
    fun `throughput is only predicted when there is a measurement behind it`() {
        val withoutCalibration =
            estimator.estimate(phone, hybrid, fileSizeBytes = 1670 * MIB, contextLength = 4096)
        assertThat(withoutCalibration.estimatedDecodeTokensPerSecond).isNull()

        // Measured on the dev device: 1.67 GB at 18 tok/s. A model twice the size should
        // predict roughly half the throughput, because decode is bandwidth-bound.
        val calibrated = estimator.estimate(
            phone,
            hybrid,
            fileSizeBytes = 3340 * MIB,
            contextLength = 4096,
            calibration = ThroughputCalibration(1670 * MIB, 18.0),
        )
        assertThat(calibrated.estimatedDecodeTokensPerSecond).isWithin(0.1).of(9.0)
    }

    @Test
    fun `prefill throughput is predicted independently of decode throughput`() {
        val withoutCalibration =
            estimator.estimate(phone, hybrid, fileSizeBytes = 1670 * MIB, contextLength = 4096)
        assertThat(withoutCalibration.estimatedPrefillTokensPerSecond).isNull()

        // Prefill and decode are bandwidth-bound the same way but at very different rates,
        // so one calibration must not leak into the other's estimate.
        val calibrated = estimator.estimate(
            phone,
            hybrid,
            fileSizeBytes = 3340 * MIB,
            contextLength = 4096,
            calibration = ThroughputCalibration(1670 * MIB, 18.0),
            prefillCalibration = ThroughputCalibration(1670 * MIB, 70.0),
        )
        assertThat(calibrated.estimatedDecodeTokensPerSecond).isWithin(0.1).of(9.0)
        assertThat(calibrated.estimatedPrefillTokensPerSecond).isWithin(0.1).of(35.0)
    }
}

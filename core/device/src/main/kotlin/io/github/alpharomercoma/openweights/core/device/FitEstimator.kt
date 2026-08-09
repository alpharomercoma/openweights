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

import io.github.alpharomercoma.openweights.core.common.model.GgufMetadata
import javax.inject.Inject

/** How well a model file suits this device. */
enum class FitVerdict {
    /** Fits with room to spare. */
    COMFORTABLE,

    /** Runs, but close enough to the limit that other apps may be evicted. */
    TIGHT,

    /** Will not load at this context length. */
    WONT_RUN,

    /** Not enough free storage to download it. */
    NO_ROOM_TO_DOWNLOAD,
}

/**
 * What the app can tell a user about a model before they download it.
 *
 * @param requiredMemoryBytes weights plus KV cache plus runtime overhead.
 * @param estimatedDecodeTokensPerSecond null when there is no calibration to base it on;
 *   an invented number is worse than none.
 * @param maxContextLength the largest context this device can hold for this model.
 */
data class FitReport(
    val verdict: FitVerdict,
    val requiredMemoryBytes: Long,
    val usableMemoryBytes: Long,
    val kvCacheBytes: Long,
    val estimatedDecodeTokensPerSecond: Double?,
    val maxContextLength: Int,
) {
    val headroomBytes: Long get() = usableMemoryBytes - requiredMemoryBytes
}

/**
 * Decides whether a model runs on this device, and how fast.
 *
 * The honesty of this is the product. Every on-device app can list models; the useful
 * thing is saying "this one will not load" before someone spends a gigabyte of mobile data
 * finding out. So the estimate is deliberately conservative, and throughput is only
 * reported when there is a real measurement to extrapolate from.
 */
class FitEstimator @Inject constructor() {

    @Suppress("LongParameterList")
    fun estimate(
        device: DeviceProfile,
        metadata: GgufMetadata,
        fileSizeBytes: Long,
        contextLength: Int,
        /** Measured decode throughput for a model of known size on this device, if any. */
        calibration: ThroughputCalibration? = null,
    ): FitReport {
        val kvCache = metadata.kvCacheBytes(contextLength)
        val required = fileSizeBytes + kvCache + RUNTIME_OVERHEAD_BYTES
        val usable = device.usableMemoryBytes

        val verdict = when {
            device.freeStorageBytes < fileSizeBytes + STORAGE_MARGIN_BYTES ->
                FitVerdict.NO_ROOM_TO_DOWNLOAD

            required > usable -> FitVerdict.WONT_RUN
            required > usable * TIGHT_FRACTION -> FitVerdict.TIGHT

            // Fits the device, but not what the device has free right now: Android will
            // have to evict other apps to make room. That is a real cost to the user, so
            // it is reported rather than hidden behind a comfortable verdict.
            required > device.availableMemoryBytes -> FitVerdict.TIGHT

            else -> FitVerdict.COMFORTABLE
        }

        return FitReport(
            verdict = verdict,
            requiredMemoryBytes = required,
            usableMemoryBytes = usable,
            kvCacheBytes = kvCache,
            estimatedDecodeTokensPerSecond = calibration?.predictFor(fileSizeBytes),
            maxContextLength = maxContextLength(device, metadata, fileSizeBytes),
        )
    }

    /**
     * The longest context this device can hold for this model, capped by what the model
     * was trained for — offering more than that produces gibberish, not a longer memory.
     */
    fun maxContextLength(device: DeviceProfile, metadata: GgufMetadata, fileSizeBytes: Long): Int {
        val spare = device.usableMemoryBytes - fileSizeBytes - RUNTIME_OVERHEAD_BYTES
        if (spare <= 0) return 0

        val bytesPerToken = metadata.kvCacheBytes(contextLength = 1)
        if (bytesPerToken <= 0) return metadata.trainingContextLength

        val affordable = (spare / bytesPerToken).toInt()
        val trained = metadata.trainingContextLength.takeIf { it > 0 } ?: affordable
        return minOf(affordable, trained)
    }

    private companion object {
        /**
         * Compute buffers, the tokenizer, the app itself, and the JVM heap. Measured at
         * roughly 300 MB on the dev device; rounded up because underestimating this is how
         * an app promises a fit and then gets killed.
         */
        const val RUNTIME_OVERHEAD_BYTES = 450L * 1024 * 1024

        /** Leave room for the download plus a little, so the device is not left at zero. */
        const val STORAGE_MARGIN_BYTES = 512L * 1024 * 1024

        /** Above this fraction of usable memory, other apps start getting evicted. */
        const val TIGHT_FRACTION = 0.8
    }
}

/**
 * A measurement of how fast this device actually decoded a model of a known size.
 *
 * Decode is bandwidth-bound — throughput scales roughly with the reciprocal of the bytes
 * touched per token — so one honest measurement predicts other model sizes far better
 * than any table of chip names would.
 */
data class ThroughputCalibration(val measuredBytes: Long, val measuredTokensPerSecond: Double) {
    fun predictFor(fileSizeBytes: Long): Double? {
        if (fileSizeBytes <= 0 || measuredBytes <= 0 || measuredTokensPerSecond <= 0) return null
        return measuredTokensPerSecond * (measuredBytes.toDouble() / fileSizeBytes)
    }
}

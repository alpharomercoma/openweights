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
 * This is the product. Every on-device app can list models; the useful
 * thing is saying "this one will not load" before someone spends a gigabyte of mobile data
 * finding out. So the estimate is conservative, and throughput is only
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
        /**
         * The multimodal projector downloaded alongside this model, if any.
         *
         * Counted in full because it is loaded in full: for a small vision model the
         * projector can be most of the weights again, and a fit report that ignored it
         * would call a model comfortable and then run the phone out of memory.
         */
        projectorSizeBytes: Long = 0,
    ): FitReport {
        val kvCache = metadata.kvCacheBytes(contextLength)
        val weights = fileSizeBytes + projectorSizeBytes
        val required = weights + kvCache + RUNTIME_OVERHEAD_BYTES
        val usable = device.usableMemoryBytes

        val verdict = when {
            device.freeStorageBytes < weights + STORAGE_MARGIN_BYTES ->
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
            maxContextLength = maxContextLength(device, metadata, weights),
        )
    }

    /**
     * The longest context this device can hold for this model, capped by what the model
     * was trained for: offering more than that produces gibberish, not a longer memory.
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

    /**
     * The largest model, in billions of parameters, worth showing this device.
     *
     * A coarse figure on purpose. The Hub filters by parameter count, which is all that is
     * known before a repository is opened, and the honest answer for any one file needs
     * its header. This is the bound at the quantization almost everyone downloads,
     * Q4_K_M, with room left for the context window and the runtime, so what comes back is
     * a list where opening something is usually worth it.
     */
    fun parameterCeilingBillions(device: DeviceProfile): Int {
        val forWeights = device.usableMemoryBytes - RUNTIME_OVERHEAD_BYTES - MODEST_KV_CACHE_BYTES
        if (forWeights <= 0) return 1
        return (forWeights / BYTES_PER_BILLION_Q4).toInt().coerceAtLeast(1)
    }

    private companion object {
        /**
         * Compute buffers, the tokenizer, the app itself, and the JVM heap. Measured at
         * roughly 300 MB on the dev device; rounded up because underestimating this is how
         * an app promises a fit and then gets killed.
         */
        const val RUNTIME_OVERHEAD_BYTES = 450L * 1024 * 1024

        /**
         * Q4_K_M averages a little under five bits a weight, so a billion parameters costs
         * about 0.6 GB on disk and the same in memory.
         */
        const val BYTES_PER_BILLION_Q4 = 600L * 1024 * 1024

        /** A few thousand tokens of context, which is what the app defaults to. */
        const val MODEST_KV_CACHE_BYTES = 400L * 1024 * 1024

        /** Leave room for the download plus a little, so the device is not left at zero. */
        const val STORAGE_MARGIN_BYTES = 512L * 1024 * 1024

        /** Above this fraction of usable memory, other apps start getting evicted. */
        const val TIGHT_FRACTION = 0.8
    }
}

/**
 * A measurement of how fast this device actually decoded a model of a known size.
 *
 * Decode is bandwidth-bound: throughput scales roughly with the reciprocal of the bytes
 * touched per token, so one measurement predicts other model sizes far better
 * than any table of chip names would.
 */
data class ThroughputCalibration(val measuredBytes: Long, val measuredTokensPerSecond: Double) {
    fun predictFor(fileSizeBytes: Long): Double? {
        if (fileSizeBytes <= 0 || measuredBytes <= 0 || measuredTokensPerSecond <= 0) return null
        return measuredTokensPerSecond * (measuredBytes.toDouble() / fileSizeBytes)
    }
}

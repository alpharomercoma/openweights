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
     * The window to open a model with when nobody has chosen one.
     *
     * The app shipped 4096 for every model on every phone, which is three separate mistakes
     * at once. It is far below what a modern small model was trained for, so a conversation
     * folds long before it had to. It takes no account of the device, so a phone with twelve
     * gigabytes gets what a phone with four gets. And it is not bounded above by the model
     * either: a model trained to 2048 was being asked for 4096, and llama.cpp allows that,
     * so past its training length the answers quietly degrade.
     *
     * So: as much as the model was trained for, as much as this phone can hold, whichever is
     * smaller. Both halves matter. Measured on an MT6991 with LFM2.5 2.6B, every width from
     * 4k to 128k loaded, decode stayed between 15.7 and 16.5 tokens a second across the whole
     * range, and load time moved by two and a half seconds over a thirty-two-fold increase.
     * A wide window that nobody has filled costs almost nothing, because the cache is
     * allocated lazily and only the pages a conversation reaches are ever resident.
     *
     * What stops it simply being the trained maximum is the phone rather than the model. A
     * hybrid like LFM2.5 keeps a cache for eight of its thirty layers, which is 16 KB a
     * token; a transformer of half the size with attention everywhere is seven times that,
     * and its trained window would ask for more memory than the device has. [KV_FRACTION] is
     * what keeps the difference from becoming a promise the phone cannot keep, and it is a
     * share of usable memory rather than a fixed number of tokens because the thing that
     * varies between two phones is the memory, not the arithmetic.
     */
    fun defaultContextLength(
        device: DeviceProfile,
        metadata: GgufMetadata,
        fileSizeBytes: Long,
        projectorSizeBytes: Long = 0,
    ): Int {
        // Bounded above as well as below, and by less than the header says.
        //
        // `<arch>.context_length` is `max_position_embeddings`: how far the positional
        // encoding can reach, not how far the model was trained. It is systematically
        // optimistic and the gap is large. LFM2.5 1.2B Instruct writes 128,000 and its model
        // card says 32,768; Qwen3 1.7B writes 40,960 and its card says 32,768. The real
        // figure is prose on a web page and there is no key for it, so opening at the header
        // is opening past what the publisher validated, which is the thing this was supposed
        // to stop rather than cause.
        //
        // So the header stays the hard bound and the automatic window stays inside it, at
        // [SAFE_CONTEXT]. The argument for that number is that the cap can only ever bite on
        // a model whose header claims more than it: anything trained to 2,048 or 4,096 writes
        // that and the minimum takes it, cap or no cap. What is left is models claiming more
        // than 32,768, and those are recent ones whose cards state at least 32,768, both of
        // the two checked here included. Anyone who wants the rest can move the slider, which
        // still runs to what the header allows.
        val declared = metadata.trainingContextLength
            .takeIf { it > 0 }
            ?.coerceAtMost(MAX_PLAUSIBLE_CONTEXT)
            ?: FALLBACK_CONTEXT
        val trained = minOf(declared, SAFE_CONTEXT)
        val bytesPerToken = metadata.kvCacheBytes(contextLength = 1)
        // A header that says nothing about its cache shape can still be bounded by what the
        // model was trained for. Returning the fallback outright, which is what this did,
        // ignored the device entirely on exactly the files least worth trusting.
        if (bytesPerToken <= 0) return minOf(trained, FALLBACK_CONTEXT)

        // Two ceilings, and the smaller wins. One is the share of the phone a cache may
        // claim; the other is what is actually left once the weights are in memory, which on
        // a device that barely fits this model is the tighter of the two.
        val share = ((device.usableMemoryBytes * KV_FRACTION).toLong() / bytesPerToken).toInt()
        val remaining = maxContextLength(device, metadata, fileSizeBytes + projectorSizeBytes)

        // Rounded down to a whole number of blocks, because a window is a buffer and an odd
        // size reads as a sum rather than a size.
        val chosen = minOf(trained, share, remaining) / CONTEXT_BLOCK * CONTEXT_BLOCK

        // The floor is a floor only where there is room for it, and never above what the
        // model was trained for. Written as coerceIn, it threw: coerceIn requires its minimum
        // to be at or below its maximum, and a model trained to 1024 makes 2048..1024, which
        // is an IllegalArgumentException out of a load rather than a small window. And a
        // device that cannot hold the weights at all must not be handed a floor it also
        // cannot hold, on top of a model that was never going to fit.
        // The floor stands even where nothing fits. A model whose weights alone exhaust the
        // device is not going to load at any width, and handing back zero would make the
        // engine choose its own default instead of failing with something the user can read.
        val floor = minOf(MIN_CONTEXT, trained)
        return maxOf(chosen, floor).coerceAtMost(trained)
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

        /**
         * The share of usable memory a KV cache may claim by default.
         *
         * A third, so the weights, the cache and everything else each have room and the
         * process stays a size the platform will let sit in the background. Chosen rather
         * than measured: what was measured is that a wide window costs nothing until it is
         * filled, which says the ceiling should be about what happens when it *is* filled,
         * and that is when the app is killed rather than when it is slow.
         */
        const val KV_FRACTION = 0.33

        /** What to open with when the header says nothing useful: what the app always did. */
        const val FALLBACK_CONTEXT = 4_096

        /** Short enough to be cheap, long enough to answer a question in. */
        const val MIN_CONTEXT = 2_048

        /** Windows are rounded down to this, so the number reads as a size rather than a sum. */
        const val CONTEXT_BLOCK = 1_024

        /**
         * Past this, a header is claiming something no phone will reach in a conversation.
         *
         * A million tokens at the measured 27 tokens a second of prefill is ten hours, so the
         * number stops describing anything a user can do and starts describing a file's own
         * opinion of itself.
         */
        const val MAX_PLAUSIBLE_CONTEXT = 262_144

        /**
         * The widest window to open without being told to.
         *
         * A safety bound rather than a claim about competence, and the difference matters.
         * It says this much can be addressed and held; it does not say the model is any good
         * that far out, because nothing machine-readable says that and this number cannot
         * invent it.
         *
         * 32,768 because a cap only bites on a model claiming more than it. Anything trained
         * to 2,048 or 4,096 says so in its header and the minimum takes it. What is left is
         * the recent models claiming six figures, and their cards, where they say anything,
         * say 32,768: LFM2.5 1.2B Instruct and Qwen3 1.7B both do, against headers of 128,000
         * and 40,960. Memory is bounded before this and lands far below it on a small phone,
         * so raising it from 16,384 changes nothing where memory is tight and costs about
         * fifty megabytes where it is not.
         */
        const val SAFE_CONTEXT = 32_768
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

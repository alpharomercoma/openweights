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

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.system.measureNanoTime

/**
 * How fast this phone can read its own memory, which is what decode speed is made of.
 *
 * Generating a token reads essentially every weight once, so decode throughput is
 * bandwidth divided by model size and almost nothing else. That is why one measured
 * (bytes, tokens a second) pair predicts every other model's speed, and it is the whole
 * premise of [ThroughputCalibration].
 *
 * The problem this solves is that the pair has to come from somewhere. Until now it came
 * from a model this device had already run, which means a phone that has just installed
 * the app has none — so Discover could say whether a model *fits* and never whether it
 * would be usable. Someone downloads four gigabytes over a phone connection, waits, and
 * finds out it answers at walking pace. That is the first impression the app cannot
 * afford, and it is entirely avoidable: the device's bandwidth can be measured in a
 * fraction of a second without running anything.
 *
 * Measured on the dev phone (MT6991), streaming reads at the thread count decode uses:
 *
 * | threads | GB/s |
 * | --- | --- |
 * | 1 | 16.0 |
 * | 2 | 35.3 |
 * | 4 | 31.9-38.4 |
 * | 8 | 45.1-45.8 |
 *
 * against a measured 30 tokens a second on a 663 MB model, which is 19.9 GB/s of useful
 * weight traffic. [DECODE_EFFICIENCY] is that ratio. The gap is dequantization, the KV
 * cache, activations, and the graph's own serial sections — real work that a raw
 * streaming loop does not do.
 */
@Singleton
class MemoryBandwidth @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profiler: DeviceProfiler,
) {
    private val store by lazy {
        context.getSharedPreferences("device_bandwidth", Context.MODE_PRIVATE)
    }

    /**
     * Bytes a second this device streams, measured once and remembered.
     *
     * Cached against the build rather than measured per launch: the answer is a property
     * of the hardware, the measurement allocates [BUFFER_BYTES] which is not free on a
     * phone that is already tight, and nothing about it changes between runs. Re-measured
     * when the app updates, so a build that changes the thread plan is not read against
     * an old number.
     */
    suspend fun bytesPerSecond(): Long {
        // The floor applies to what was stored as much as to what is measured. A build
        // that measured badly and wrote the result down would otherwise keep answering
        // from it forever, and the first version of this measured 1.0 GB/s on a phone
        // that does 38.
        store.getLong(keyFor(), 0L)
            .takeIf { it >= MIN_PLAUSIBLE_BYTES_PER_SECOND }
            ?.let { return it }
        val measured = measure()
        if (measured > 0) {
            store.edit().putLong(keyFor(), measured).apply()
            // Logged because it is the number every speed estimate on the browse screen is
            // derived from, and a wrong one would be invisible otherwise.
            Log.i(
                "OpenWeights",
                "bandwidth: %.1f GB/s streamed, so a 1 GB model should decode near %.1f tok/s"
                    .format(
                        measured.toDouble() / BYTES_PER_GIGABYTE,
                        measured * DECODE_EFFICIENCY / REFERENCE_BYTES,
                    ),
            )
        }
        return measured
    }

    /**
     * A stand-in for the measurement a device that has run nothing does not have yet.
     *
     * Anchored at [REFERENCE_BYTES] so that [ThroughputCalibration.predictFor] scales it
     * to any file: the pair means "a one gigabyte model would decode at this rate", and
     * the reciprocal relationship does the rest.
     *
     * Deliberately decode only. Prefill reads each weight once per *batch* rather than per
     * token, so it is compute-bound, and its measured rate on this phone (96 tokens a
     * second on a 663 MB model, or 63 GB/s of nominal weight traffic) is higher than the
     * memory system can actually stream. Predicting it from bandwidth would be a confident
     * number about the wrong bottleneck, so nothing is claimed about prefill and that
     * estimate stays absent until a real one exists.
     */
    suspend fun decodeCalibration(): ThroughputCalibration? = calibrationFor(bytesPerSecond())

    /**
     * Streams an array too large to sit in any cache and reports the best pass.
     *
     * Best rather than mean, because everything that goes wrong in a sample makes it
     * slower: a scheduler preemption, another app waking, a collection. The fastest pass
     * is the one least polluted by all of it, which is what the hardware is capable of and
     * what the prediction wants.
     *
     * A primitive `LongArray`, and that detail is the measurement. The first version of
     * this read a direct `LongBuffer`, which is a bounds-checked call per element rather
     * than a load, and it reported **1.0 GB/s on a phone a native loop measures at 31 to
     * 45**. It was timing the access path, not the memory. An array read compiles to a
     * load the same way the engine's own kernels do.
     */
    private suspend fun measure(): Long = withContext(Dispatchers.Default) {
        runCatching {
            val threads = max(1, profiler.profile().cpuCores / 2)
            val words = BUFFER_BYTES / Long.SIZE_BYTES
            val data = LongArray(words) { it.toLong() }

            var best = 0L
            // The first pass is thrown away: it is the one paying for this loop to be
            // compiled, and on a cold process it reads several times slower than the rest.
            repeat(PASSES + 1) { pass ->
                val nanos = measureNanoTime { readAcross(data, threads) }
                if (pass > 0 && nanos > 0) {
                    val rate = BUFFER_BYTES.toLong() * NANOS_PER_SECOND / nanos
                    if (rate > best) best = rate
                }
            }
            // A number below this is not a slow phone, it is a broken measurement — the
            // buffer fitting in cache, the loop not compiling, the device descheduling the
            // whole thing. Anything that runs a language model at all clears it by an
            // order of magnitude. Reporting nothing leaves the speed line absent, which is
            // where it was before any of this and is the honest answer to a bad sample.
            if (best < MIN_PLAUSIBLE_BYTES_PER_SECOND) 0L else best
        }.getOrDefault(0L)
    }

    private suspend fun readAcross(data: LongArray, threads: Int) = coroutineScope {
        val span = data.size / threads
        (0 until threads).map { slice ->
            async(Dispatchers.Default) {
                val from = slice * span
                val until = if (slice == threads - 1) data.size else from + span
                readSpan(data, from, until)
            }
        }.awaitAll()
    }

    /**
     * Eight loads and four accumulators per iteration, which is not premature.
     *
     * A single running sum makes every load wait on the previous add, so the loop measures
     * the dependency chain rather than the memory: written that way it reported 6.8 GB/s
     * on a phone a native loop measures at 38. Independent accumulators let the loads
     * overlap, which is the only way a read loop reaches what the memory system can do,
     * and it is what the engine's own kernels are doing when they stream weights.
     */
    // The offsets are the unrolling; naming them would be eight constants that say
    // "one" through "seven".
    @Suppress("MagicNumber")
    private fun readSpan(data: LongArray, from: Int, until: Int): Long {
        var a = 0L
        var b = 0L
        var c = 0L
        var d = 0L
        var i = from
        val unrolledEnd = until - UNROLL
        while (i <= unrolledEnd) {
            a += data[i] + data[i + 1]
            b += data[i + 2] + data[i + 3]
            c += data[i + 4] + data[i + 5]
            d += data[i + 6] + data[i + 7]
            i += UNROLL
        }
        while (i < until) {
            a += data[i]
            i++
        }
        return a + b + c + d
    }

    private fun keyFor(): String = "bytes_per_second_v1_" + runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    companion object {
        /**
         * How much of this probe's read rate turns into real weight traffic, and then
         * some margin.
         *
         * Fitted against the probe above rather than against a native loop, because that
         * is what the app runs. On the dev phone (MT6991) it reads **27.3 GB/s**, while
         * the engine measured **30 tokens a second on a 663 MB model**, which is 19.9 GB/s
         * of weights, so the honest ratio is 0.73. The gap is dequantization, the KV
         * cache, activations and the graph's serial sections.
         *
         * 0.60 is used instead, which predicts 24.7 against a real 30: **about twenty
         * percent under, deliberately.** This number exists to stop somebody spending
         * twenty minutes of their connection on a model that will answer at walking pace,
         * and an estimate that flatters the phone fails at the only job it has. Erring low
         * shows the warning slightly too often; erring high does not show it at all.
         */
        const val DECODE_EFFICIENCY = 0.60

        /**
         * Large enough to miss every cache on phones with a big system-level cache, small
         * enough to be an allocation the app can make while somebody is browsing.
         */
        const val BUFFER_BYTES = 48 * 1024 * 1024

        const val PASSES = 3

        /** Loads per iteration; four independent accumulators over eight elements. */
        const val UNROLL = 8
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** Decimal, because that is how storage and memory rates are quoted. */
        const val BYTES_PER_GIGABYTE = 1_000_000_000.0

        /**
         * Below this the sample is wrong rather than the phone being slow.
         *
         * Two gigabytes a second is under a tenth of what the slowest device this app
         * targets manages, and well under what any phone that can hold a model in memory
         * has to be capable of.
         */
        const val MIN_PLAUSIBLE_BYTES_PER_SECOND = 2_000_000_000L

        /** The size the seeded pair is expressed against; any other scales from it. */
        const val REFERENCE_BYTES = 1024L * 1024 * 1024

        /**
         * The arithmetic on its own, so it can be checked without a phone attached.
         *
         * Null rather than zero for a bandwidth that could not be measured: an absent
         * estimate shows nothing, and a zero would show a model decoding at no tokens a
         * second, which is a claim rather than a silence.
         */
        fun calibrationFor(bytesPerSecond: Long): ThroughputCalibration? {
            if (bytesPerSecond <= 0) return null
            return ThroughputCalibration(
                measuredBytes = REFERENCE_BYTES,
                measuredTokensPerSecond = bytesPerSecond * DECODE_EFFICIENCY / REFERENCE_BYTES,
            )
        }
    }
}

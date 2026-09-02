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

package io.github.alpharomercoma.openweights.core.common.device

import java.io.File

/**
 * How many cores are worth giving a math thread.
 *
 * Prompt processing was handed every core the phone reports, on the reasoning that it is
 * compute bound and scales. It does scale, until the cores stop being the same core.
 * Measured on a Snapdragon 8 Gen 3 (SM8650: one Cortex-X4, five A720, two A520) with
 * LFM2.5 2.6B Q4_0, each reading taken from under 56 C so heat is not doing the talking:
 *
 * | threads | prefill |
 * | ---: | ---: |
 * | 3 | 91.2 t/s |
 * | 4 | 104.9 t/s |
 * | 5 | 121.7 t/s |
 * | **6** | **139.0 t/s** |
 * | 7 | 125.6 t/s |
 * | 8 | 105.2 t/s |
 *
 * Eight threads, which is what this phone was being given, runs prompt processing at 76% of
 * what six does. The mechanism is the barrier at the end of every operation: ggml hands each
 * thread an equal slice, so the step costs whatever the slowest thread costs, and a slice on
 * an A520 costs about twice a slice on an A720. Two extra workers do not pay for slowing the
 * other six down.
 *
 * Pinning is not the answer and was measured too: six threads restricted to cores 2 to 7
 * gave 138.8 t/s against 137.3 unpinned. Given six busy threads the scheduler already puts
 * them on the six fast cores. What it cannot do is refuse the seventh and eighth.
 *
 * So the rule is about counting, not placement: **drop the slowest cluster when at least
 * half the cores are left without it.**
 *
 * | chip | clusters | picks |
 * | --- | --- | ---: |
 * | SM8650 | 1 + 5 + 2 | 6, and 6 is what measured fastest |
 * | Dimensity MT6991 | 8 at one speed | 8, which is what measured fastest there |
 * | Snapdragon 8 Elite | 2 + 6, both fast | 6: all but two, see [SPARE_CORES] |
 * | a 4 + 4 phone, the commonest Android shape | 4 + 4 | 4 |
 * | a 2 + 6 phone | 2 + 6 | 8 |
 *
 * The threshold is where it is because of the same barrier. Using the k fastest cores costs
 * `work / (k * the speed of the slowest core used)`, so dropping a cluster pays exactly when
 * the survivors' count times their speed beats the whole chip's count times a little core's
 * speed. A little core does roughly half a big core's work per step here, which puts the
 * break-even near half the cores: keep four of eight and win, keep two of eight and lose.
 *
 * Only the slowest cluster goes. A three cluster phone keeps its middle one, because nothing
 * measured here says dropping two was ever right, and guessing past the evidence is how the
 * constant this replaces came to be written.
 */
object CpuTopology {

    /** Every core, which is what [Runtime.availableProcessors] means on Android. */
    val allCores: Int get() = Runtime.getRuntime().availableProcessors()

    /**
     * The cores a batch of prompt tokens should be spread over.
     *
     * Falls back to [allCores] whenever the kernel will not say, which is the honest answer
     * rather than a guess: an unreadable `cpufreq` means no evidence that any core is
     * slower, and the old behaviour is what was measured on the phones that do read.
     */
    fun performanceCores(): Int = performanceCores(maxFrequencies())

    /**
     * The same decision over readings already taken, which is what makes it testable
     * anywhere: a unit test host has no `/sys/devices/system/cpu` to describe a phone with.
     */
    internal fun performanceCores(frequencies: List<Long>?): Int {
        if (frequencies == null || frequencies.size < MIN_CORES_TO_SPLIT) return allCores

        val slowest = frequencies.min()
        val fastCount = frequencies.count { it > slowest }
        // Nothing to drop when every core runs at one speed, and nothing worth dropping
        // when what survives is a minority: see the note above this object for why the
        // line falls at half rather than anywhere else.
        if (fastCount == 0) return frequencies.size
        if (fastCount * 2 < frequencies.size) return frequencies.size - SPARE_CORES
        return fastCount
    }

    /**
     * Every core's ceiling in kHz, or null if any of them will not say.
     *
     * All or nothing on purpose. A partial reading would make a chip look uniform exactly
     * when the cores that were refused are the ones that differ, and dropping cores on that
     * basis is worse than not dropping any.
     *
     * `cpuinfo_max_freq` rather than `scaling_max_freq`: the second is what the governor is
     * allowing right now, which moves with heat and with whatever else is running, and a
     * thread count that changes because a background app woke up is not a thread count.
     */
    private fun maxFrequencies(): List<Long>? {
        val cores = allCores
        if (cores <= 0) return null
        val read = ArrayList<Long>(cores)
        for (core in 0 until cores) {
            val value = runCatching {
                File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                    .readText()
                    .trim()
                    .toLong()
            }.getOrNull()
            if (value == null || value <= 0) return null
            read += value
        }
        return read
    }

    /** Below this a phone has no clusters worth the name, and every core is needed. */
    private const val MIN_CORES_TO_SPLIT = 4

    /**
     * Held back on a chip whose fast cluster is a minority, measured on a Snapdragon 8 Elite
     * (two Oryon cores at 4.47 GHz, six at 3.53) with Qwen3-1.7B Q8_0 on 2026-09-03:
     *
     * | threads | prefill |
     * | ---: | ---: |
     * | 4 | 318 t/s |
     * | **6** | **345 t/s** |
     * | 8 | 92 t/s |
     *
     * Every core had been the answer here, on the grounds that the six are not slow. They
     * are not: with all eight busy the step waits on whichever thread the scheduler put
     * behind the caller, and a short prompt paid a fixed three and a half seconds for it,
     * every call, whatever its length. The parity matrix read that as a phone that prefills
     * at 10 tokens a second. Two spare cores is where the cost went away; one was not
     * measured, so the number is the measured one. A chip whose cores all run at one speed
     * keeps every core, because the Dimensity 9400 measured fastest that way.
     */
    private const val SPARE_CORES = 2
}

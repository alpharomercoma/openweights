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
 * | Dimensity MT6991 | 4 A720 + 3 X4 + 1 X925 | 8, which is what measured fastest there |
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
    fun performanceCores(): Int = performanceCores(maxFrequencies(), cpuParts())

    /**
     * The same decision over readings already taken, which is what makes it testable
     * anywhere: a unit test host has no `/sys/devices/system/cpu` to describe a phone with.
     *
     * [parts] are the cores' part numbers from `/proc/cpuinfo`, and they decide what a
     * slow cluster is. A frequency cannot: the Dimensity 9400 keeps four Cortex-A720s at
     * 2.4 GHz beside four faster cores, and by frequency they look like the Snapdragon 8
     * Gen 3's two A520s at 2.27, which are the cores the rule exists to drop. They are not
     * the same core. An A520 is an in-order core that does about half an A720's work per
     * step; an A720 at 2.4 GHz does two thirds of an X4's, and dropping four of them cost
     * the Dimensity a quarter of its prompt speed (72 tok/s on four threads against 96 on
     * eight, measured with Qwen3-1.7B Q8_0 on 2026-09-03). So a cluster is dropped when
     * its cores are little by design, and only then; the frequency shape is the fallback
     * for a kernel that will not say what its cores are.
     */
    internal fun performanceCores(frequencies: List<Long>?, parts: List<Int>? = null): Int {
        if (frequencies == null || frequencies.size < MIN_CORES_TO_SPLIT) return allCores
        val size = frequencies.size

        val littles = parts?.takeIf { it.size == size }?.count { it in LITTLE_PARTS }
        val slowest = frequencies.min()
        val fastCount = frequencies.count { it > slowest }
        return when {
            // Drop the little cores when at least half the chip is left without them;
            // when they are the majority the fast cores cannot carry the chip's worth of
            // work on their own, and the littles keep their place in the barrier.
            littles != null && littles > 0 -> (size - littles).takeIf { it * 2 >= size } ?: size
            // Nothing to drop when every core runs at one speed.
            fastCount == 0 -> size
            // When the fast cluster is a minority, hold two cores back: see [SPARE_CORES].
            fastCount * 2 < size -> size - SPARE_CORES
            // No little cores among them, and the fast ones at least half: every core is
            // a big core and every core is worth a thread (the Dimensity 9400). Only a
            // kernel that would not name its cores is left to the frequency shape.
            littles == null -> fastCount
            else -> size
        }
    }

    /**
     * Every core's part number, in core order, or null if the kernel will not say.
     *
     * `/proc/cpuinfo` lists each online core with its implementer and part; a phone
     * with a core offline at that moment reads short, and a short list is treated as no
     * answer rather than matched against the wrong cores.
     */
    private fun cpuParts(): List<Int>? = runCatching {
        File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("CPU part") }
            .map { it.substringAfter(':').trim().removePrefix("0x").toInt(HEX) }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

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

    private const val HEX = 16

    /**
     * Arm's in-order cores, the ones a prompt is better off without: Cortex-A53, A35,
     * A55, A510 and A520. Everything else Arm ships in a phone is out of order and earns
     * its thread; so does every Oryon core, which Qualcomm numbers its own way and which
     * is handled by the frequency rule above.
     */
    private val LITTLE_PARTS = setOf(0xd03, 0xd04, 0xd05, 0xd46, 0xd80)
}

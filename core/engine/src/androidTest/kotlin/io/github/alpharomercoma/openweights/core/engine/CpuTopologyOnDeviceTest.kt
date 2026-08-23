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

package io.github.alpharomercoma.openweights.core.engine

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.device.CpuTopology
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether an app process can see the shape of the chip it is running on.
 *
 * `CpuTopology` decides how many threads read a prompt, and it decides it from
 * `/sys/devices/system/cpu/cpu*&#47;cpufreq/cpuinfo_max_freq`. An adb shell can read that
 * file on every device tried; an app is a different SELinux domain and does not inherit
 * that, and if the read is refused the fallback quietly restores the behaviour this was
 * written to replace. Nothing but running it here says which happens.
 *
 * This asserts the read works rather than assuming it, and logs what it found, because the
 * interesting output is the frequency table itself: it is the evidence for the thread count
 * this phone will be given.
 */
@RunWith(AndroidJUnit4::class)
class CpuTopologyOnDeviceTest {

    @Test
    fun readsTheFrequencyTableFromInsideAnAppProcess() {
        val cores = CpuTopology.allCores
        val frequencies = (0 until cores).map { core ->
            core to runCatching {
                File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLong()
            }.getOrNull()
        }
        Log.i(TAG, "soc=${Build.SOC_MODEL} hardware=${Build.HARDWARE} cores=$cores")
        frequencies.forEach { (core, khz) ->
            Log.i(TAG, "cpu$core cpuinfo_max_freq=${khz ?: "refused"}")
        }
        Log.i(TAG, "performanceCores=${CpuTopology.performanceCores()} of $cores")

        // The point of the test. A refusal is not a crash, it is a silently worse thread
        // count, so it has to fail here rather than be discovered as a slow phone later.
        assertThat(frequencies.filter { it.second == null }.map { it.first }).isEmpty()
    }

    private companion object {
        const val TAG = "OpenWeights"
    }
}

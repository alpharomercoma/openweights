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
import android.os.PowerManager
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How many threads to use right now, given how hot the phone is.
 *
 * @param shouldPause true when the device is hot enough that starting more work is the
 *   wrong thing to do at all.
 */
data class ThreadPlan(
    val generateThreads: Int,
    val batchThreads: Int,
    val shouldPause: Boolean = false,
)

/**
 * Picks thread counts from the device's current thermal state.
 *
 * Measured on the dev device: cold, prompt processing scaled all the way to eight threads
 * (69.5 tok/s); after a long run of inference the same benchmark gave 73.6 tok/s at *two*
 * threads and 28.0 at eight. Once the big cores are throttled, extra threads add
 * contention instead of throughput, so a fixed count is wrong half the time.
 *
 * Sustained inference on a phone is a thermal problem as much as a compute one.
 */
@Singleton
/**
 * How hot the device is, in the four steps Android reports.
 *
 * Named for what the user would say rather than for the constant: nobody thinks "my phone
 * is at thermal status severe".
 */
enum class ThermalLevel(val label: String) {
    NONE("cool"),
    LIGHT("warm"),
    HEAVY("hot"),
    CRITICAL("very hot"),
    ;

    val isWarm: Boolean get() = this != NONE
}

class ThermalPolicy @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profiler: DeviceProfiler,
) {
    /**
     * How many threads the next generation should use.
     *
     * The core count is read here rather than passed in: it is an input to the policy, not
     * a decision the caller makes, and asking every caller to assemble it invited them to
     * assemble it differently.
     */
    fun plan(): ThreadPlan {
        val cores = profiler.profile().cpuCores
        val generate = (cores / 2).coerceIn(MIN_THREADS, MAX_GENERATE_THREADS)
        val batch = cores.coerceIn(MIN_THREADS, MAX_BATCH_THREADS)

        return when (level()) {
            ThermalLevel.NONE -> ThreadPlan(generate, batch)

            // Backing off early keeps throughput up and slows further heating, which is
            // what actually gets a long conversation finished.
            ThermalLevel.LIGHT -> ThreadPlan(
                generateThreads = (generate - 1).coerceAtLeast(MIN_THREADS),
                batchThreads = (batch / 2).coerceAtLeast(MIN_THREADS),
            )

            ThermalLevel.HEAVY -> ThreadPlan(MIN_THREADS, MIN_THREADS)

            // At critical and above Android is already shedding load to avoid shutting
            // down. Sustained inference is among the heaviest things this device can do,
            // so the right move is to stop, not to stop slightly less.
            ThermalLevel.CRITICAL -> ThreadPlan(MIN_THREADS, MIN_THREADS, shouldPause = true)
        }
    }

    /**
     * True when the phone is hot enough that the thread plan has been cut back.
     *
     * Exposed so the UI can say so. A generation that has quietly halved its thread count
     * looks identical to a slow model, and "the phone is warm" is the difference between a
     * user thinking the app is broken and knowing to put it down for a minute.
     */
    fun isThrottling(): Boolean = level().isWarm

    /**
     * How hot the system says the device is.
     *
     * The operating system's own assessment, which is the only thermal reading an ordinary
     * app can have: a temperature in degrees needs HardwarePropertiesManager, which is
     * privileged, and `/sys/class/thermal` is not readable under SELinux on any recent
     * Android. What this gives instead is better for the purpose anyway, because it is the
     * same signal the scheduler is acting on when the phone slows down.
     */
    fun level(): ThermalLevel {
        val status = context.getSystemService<PowerManager>()?.currentThermalStatus
            ?: PowerManager.THERMAL_STATUS_NONE
        return when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
            status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.HEAVY
            status >= PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
            else -> ThermalLevel.NONE
        }
    }

    private companion object {
        const val MIN_THREADS = 2
        const val MAX_GENERATE_THREADS = 6
        const val MAX_BATCH_THREADS = 8
    }
}

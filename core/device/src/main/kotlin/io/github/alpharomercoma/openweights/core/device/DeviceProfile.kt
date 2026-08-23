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

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.core.common.device.CpuTopology
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What this phone can actually give a model. */
data class DeviceProfile(
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val freeStorageBytes: Long,
    val cpuCores: Int,
    /**
     * The cores worth handing a prompt-processing thread, which is not always [cpuCores].
     *
     * Defaults to all of them, which is both what the code did before this existed and the
     * right answer for a phone whose cores are all the same speed. See [CpuTopology] for
     * the measurement that separated the two.
     */
    val performanceCores: Int = cpuCores,
    val socModel: String,
    val isLowRamDevice: Boolean,
) {
    /**
     * The most memory a model may occupy before Android starts killing things.
     *
     * Android does not hand an app the whole device: the system, the launcher, and
     * whatever else is resident all need their share, and a foreground app that grows past
     * roughly two-thirds of physical memory gets killed rather than throttled. Estimating
     * against total RAM is how apps promise a model will fit and then die loading it.
     */
    val usableMemoryBytes: Long
        get() = (totalMemoryBytes * if (isLowRamDevice) LOW_RAM_HEADROOM else HEADROOM).toLong()

    private companion object {
        const val HEADROOM = 0.65
        const val LOW_RAM_HEADROOM = 0.5
    }
}

/** Reads this device's real capabilities, with no per-chip table anywhere. */
@Singleton
class DeviceProfiler @Inject constructor(@ApplicationContext private val context: Context) {
    fun profile(): DeviceProfile {
        val activityManager = context.getSystemService<ActivityManager>()
        val memoryInfo = ActivityManager.MemoryInfo().also {
            activityManager?.getMemoryInfo(it)
        }
        val storage = StatFs(storageRoot().path)

        return DeviceProfile(
            totalMemoryBytes = memoryInfo.totalMem,
            availableMemoryBytes = memoryInfo.availMem,
            freeStorageBytes = storage.availableBlocksLong * storage.blockSizeLong,
            cpuCores = CpuTopology.allCores,
            performanceCores = CpuTopology.performanceCores(),
            socModel = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
                ?: Build.HARDWARE,
            isLowRamDevice = activityManager?.isLowRamDevice ?: false,
        )
    }

    /** Models live in app-specific external storage, so measure the volume they land on. */
    private fun storageRoot(): File = context.getExternalFilesDir(null) ?: context.filesDir
}

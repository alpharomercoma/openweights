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

package io.github.alpharomercoma.openweights.ui.chat

import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which thermal signal an app can actually see, and whether the one being used says anything.
 *
 * `ThermalPolicy` decides how many threads to use from `PowerManager.currentThermalStatus`,
 * because that is "the same signal the scheduler uses when it slows the phone down". On this
 * device that turns out not to be true of the part of the phone doing the work.
 * `dumpsys thermalservice` during a long conversation:
 *
 * ```
 * Thermal Status: 0
 *   Temperature{mValue=95.0, mName=CPU2, mStatus=3}
 *   Temperature{mValue=95.0, mName=CPU3, mStatus=3}
 *   ... CPU4, CPU5, CPU7 the same
 *   Temperature{mValue=25.0, mName=battery, mStatus=0}
 * ```
 *
 * Five of eight cores at 95 C, each flagged severe, and the device-level status the app is
 * allowed to read is zero, because that number tracks the skin rather than the silicon and
 * this is a rack unit with cooling. Every rung of the policy's ladder is therefore unreachable
 * here, including the one that stops generating at critical.
 *
 * `getThermalHeadroom` is the other signal, public since API 30, and it reports the ratio of
 * the current thermal state to the point where throttling begins. This prints both so the
 * question can be settled on any device it is run on rather than argued about.
 */
@RunWith(AndroidJUnit4::class)
class ThermalSignalOnDeviceTest {

    @Test
    fun whatTheAppCanSeeOfHowHotTheChipIs() {
        val power = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService<PowerManager>()
        requireNotNull(power)

        val status = power.currentThermalStatus
        val now = runCatching { power.getThermalHeadroom(0) }.getOrNull()
        val soon = runCatching { power.getThermalHeadroom(FORECAST_SECONDS) }.getOrNull()

        Log.i(
            TAG,
            "thermal: currentThermalStatus=$status headroomNow=$now " +
                "headroomIn${FORECAST_SECONDS}s=$soon",
        )

        // Nothing about the values, which are a property of the device and of how long it has
        // been working. What is asserted is that the second signal exists at all, because the
        // argument for using it rests on that and on nothing else.
        assertThat(status).isAtLeast(PowerManager.THERMAL_STATUS_NONE)
        assertThat(now == null && soon == null).isFalse()
    }

    private companion object {
        const val TAG = "OpenWeights"

        /** Far enough ahead to act on, close enough that the platform will forecast it. */
        const val FORECAST_SECONDS = 60
    }
}

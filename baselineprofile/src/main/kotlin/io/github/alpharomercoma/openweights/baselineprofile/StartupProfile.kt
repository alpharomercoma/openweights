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

package io.github.alpharomercoma.openweights.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Records which code the app runs on the way in, so ART can compile it ahead of time.
 *
 * Without a profile of its own the release build carries only the merged profiles of the
 * AndroidX libraries it uses, which covers the framework's share of a cold start and none of
 * ours: the Hilt graph, the Room open, the Compose tree of the first screen, and the engine's
 * backend selection are all interpreted the first time. This is the one item left on the
 * release checklist that a user would actually feel.
 *
 * What is exercised is deliberately the boring part. A cold start and one visit to each tab
 * is what every user does and what nobody wants to wait for. Loading a model is not here:
 * it is minutes of native work whose cost is the weights rather than the bytecode, and
 * profiling it would pad the profile with code that runs once an hour.
 *
 * Run with a device attached:
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile
 * ```
 */
@RunWith(JUnit4::class)
class StartupProfile {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startUpAndVisitEveryTab() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()

        // Waited for rather than assumed. The composer is the last thing the first frame
        // draws, so its arrival is what says the start-up path finished rather than that the
        // window opened.
        device.wait(Until.hasObject(By.desc("Message")), READY_MILLIS)

        // Each tab once. The bottom bar is the whole navigation surface, and a tab nobody
        // visits here is a tab whose first open is interpreted.
        listOf("Models", "Tools", "Usage", "Settings", "Chat").forEach { tab ->
            device.findObject(By.text(tab))?.click()
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE = "io.github.alpharomercoma.openweights"

        /** Long enough for a cold start on a slow phone, short enough to fail rather than hang. */
        const val READY_MILLIS = 30_000L
    }
}

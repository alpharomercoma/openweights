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
 * What is exercised is deliberately the boring part. A cold start and one visit to each of
 * the screens behind the drawer is what every user does and what nobody wants to wait for.
 * Loading a model is not here: it is minutes of native work whose cost is the weights rather
 * than the bytecode, and profiling it would pad the profile with code that runs once an hour.
 *
 * This used to open a bottom bar tab at a time. When the bar was deleted the walk kept
 * compiling, because `findObject` returns null for a label that is not on screen and the
 * click was written `?.click()`, so five taps became five silent no-ops and the profile
 * quietly shrank to a cold start. The drawer is opened by its own content description for
 * the same reason: a walk that cannot fail is a walk that stops covering anything.
 *
 * **The checked-in profile is older than this file and needs regenerating.**
 * `app/src/release/generated/baselineProfiles/baseline-prof.txt` was recorded before the
 * bottom bar was deleted and still names methods that no longer exist, `switchTab` and
 * `AttachDocumentButton` among them. ART drops entries it cannot resolve, so nothing is
 * broken by it; what is lost is the coverage of everything the redesign added. Run the task
 * below on a device and commit what it writes.
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
    fun startUpAndVisitEveryScreen() = rule.collect(packageName = PACKAGE) {
        pressHome()
        startActivityAndWait()

        // Waited for rather than assumed. The composer is the last thing the first frame
        // draws, so its arrival is what says the start-up path finished rather than that the
        // window opened.
        device.wait(Until.hasObject(By.desc("Message")), READY_MILLIS)

        // Each pushed screen once, and back to the conversation each time, which is the shape
        // the navigation now has: chat is the only destination and the rest sit on top of it.
        listOf("Tools", "Usage", "Settings").forEach { screen ->
            device.findObject(By.desc(DRAWER))?.click()
            device.wait(Until.hasObject(By.text(screen)), STEP_MILLIS)
            device.findObject(By.text(screen))?.click()
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }

        // The model picker and the sampler sheet, which are the two surfaces a first turn
        // raises and neither of which the drawer walk reaches.
        device.findObject(By.desc(SAMPLERS))?.click()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "io.github.alpharomercoma.openweights"

        /** Long enough for a cold start on a slow phone, short enough to fail rather than hang. */
        const val READY_MILLIS = 30_000L

        /** Long enough for a drawer to slide, which is not a cold start. */
        const val STEP_MILLIS = 5_000L

        /** Both are the content descriptions the top bar sets, so they move together. */
        const val DRAWER = "Past chats"
        const val SAMPLERS = "Model settings"
    }
}

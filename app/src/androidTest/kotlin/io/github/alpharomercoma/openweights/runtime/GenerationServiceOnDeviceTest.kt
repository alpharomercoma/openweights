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

package io.github.alpharomercoma.openweights.runtime

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real service, started and stopped by Android, across the boundaries of a goal.
 *
 * The unit test beside this proves the holder bookkeeping. This proves the thing that
 * bookkeeping exists to control: that the platform's own service is running throughout, which
 * is what keeps the process out of the cached state Android freezes.
 *
 * It is a device test because a service lifecycle is not something Robolectric can be asked
 * about honestly. `ActivityManager.getRunningServices` is deprecated for looking at other
 * apps and still returns an app's own services, which is all that is asked here.
 *
 * Two boundaries at minimum, because a single one cannot distinguish a hold that nests from
 * one that is dropped and re-taken quickly enough to look continuous.
 */
@RunWith(AndroidJUnit4::class)
class GenerationServiceOnDeviceTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        GenerationService.release(context, GenerationService.TURN)
        GenerationService.release(context, GenerationService.GOAL)
        settle()
    }

    /** Whether Android currently has our service running. */
    private fun serviceRunning(): Boolean {
        @Suppress("DEPRECATION")
        val running = context.getSystemService<ActivityManager>()
            ?.getRunningServices(Int.MAX_VALUE)
            .orEmpty()
        return running.any { it.service.className == GenerationService::class.java.name }
    }

    /**
     * Waits for the service state to catch up with the request.
     *
     * `startForegroundService` and `stopService` are asynchronous, so reading straight after
     * one races it. Polling rather than sleeping a fixed time keeps the test quick when the
     * platform is quick.
     */
    private fun waitFor(running: Boolean): Boolean {
        repeat(WAIT_STEPS) {
            if (serviceRunning() == running) return true
            Thread.sleep(STEP_MS)
        }
        return serviceRunning() == running
    }

    private fun settle() = Thread.sleep(STEP_MS)

    @Test
    fun theServiceSurvivesEveryStepBoundaryOfAGoal() {
        assertThat(waitFor(running = false)).isTrue()

        GenerationService.hold(context, GenerationService.GOAL, "Working on a goal")
        assertThat(waitFor(running = true)).isTrue()

        // Three steps, so two boundaries fall strictly between them.
        repeat(3) { step ->
            GenerationService.hold(context, GenerationService.TURN, "Answering")
            settle()
            assertThat(serviceRunning()).isTrue()

            // The end of a step. Before the fix this is where the service stopped, and it is
            // the window in which a backgrounded goal was frozen and never woke up.
            GenerationService.release(context, GenerationService.TURN)
            settle()
            assertThat(serviceRunning()).isTrue()
        }

        GenerationService.release(context, GenerationService.GOAL)
        assertThat(waitFor(running = false)).isTrue()
    }

    @Test
    fun oneQuestionStartsAndStopsIt() {
        GenerationService.hold(context, GenerationService.TURN, "Answering")
        assertThat(waitFor(running = true)).isTrue()

        GenerationService.release(context, GenerationService.TURN)
        assertThat(waitFor(running = false)).isTrue()
    }

    private companion object {
        const val WAIT_STEPS = 40
        const val STEP_MS = 250L
    }
}

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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The process stays raised across the gaps in a goal.
 *
 * This shipped wrong. A turn raised the process in `generate` and dropped it in
 * `releaseTurn`, which is right for one question and wrong for a goal: a goal is many turns,
 * so the service stopped at the end of every step and started again at the beginning of the
 * next. The gap between them is where a goal does its own work, deciding what is next and
 * folding the context, and backgrounded, Android is free to freeze it there. A goal frozen
 * between steps never reaches the code that would start the service again.
 *
 * Two step boundaries rather than one, because one boundary cannot tell a hold that nests
 * from a hold that merely happens to be re-taken quickly.
 */
@RunWith(RobolectricTestRunner::class)
class GenerationHoldTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        GenerationService.release(context, GenerationService.TURN)
        GenerationService.release(context, GenerationService.GOAL)
    }

    @Test
    fun `one question raises the process and lets go at the end`() {
        assertThat(GenerationService.isHeld()).isFalse()

        GenerationService.hold(context, GenerationService.TURN, "Answering")
        assertThat(GenerationService.isHeld()).isTrue()

        GenerationService.release(context, GenerationService.TURN)
        assertThat(GenerationService.isHeld()).isFalse()
    }

    @Test
    fun `a goal keeps the process up across two step boundaries`() {
        GenerationService.hold(context, GenerationService.GOAL, "Working on a goal")

        repeat(3) { step ->
            GenerationService.hold(context, GenerationService.TURN, "Answering")
            assertThat(GenerationService.isHeld()).isTrue()

            // The end of a step. Before this fix the process was dropped here, and this is
            // the window a goal is frozen in.
            GenerationService.release(context, GenerationService.TURN)
            assertThat(GenerationService.isHeld()).isTrue()
        }

        GenerationService.release(context, GenerationService.GOAL)
        assertThat(GenerationService.isHeld()).isFalse()
    }

    @Test
    fun `the goal letting go while a turn is still running does not drop the process`() {
        // Stop pressed mid-step: the goal releases, the turn has not finished unwinding.
        GenerationService.hold(context, GenerationService.GOAL, "Working on a goal")
        GenerationService.hold(context, GenerationService.TURN, "Answering")

        GenerationService.release(context, GenerationService.GOAL)
        assertThat(GenerationService.isHeld()).isTrue()

        GenerationService.release(context, GenerationService.TURN)
        assertThat(GenerationService.isHeld()).isFalse()
    }

    @Test
    fun `letting go twice is not letting go of somebody else's hold`() {
        GenerationService.hold(context, GenerationService.GOAL, "Working on a goal")
        GenerationService.release(context, GenerationService.TURN)
        GenerationService.release(context, GenerationService.TURN)

        assertThat(GenerationService.isHeld()).isTrue()
    }
}

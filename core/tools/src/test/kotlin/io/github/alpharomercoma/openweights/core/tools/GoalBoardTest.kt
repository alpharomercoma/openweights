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

package io.github.alpharomercoma.openweights.core.tools

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.context.Goal
import io.github.alpharomercoma.openweights.core.common.context.GoalState
import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import io.github.alpharomercoma.openweights.core.common.context.TaskStep
import org.junit.Test

/**
 * The bounds on a goal, which are the whole safety story.
 *
 * An autonomous loop on a small model does not fail by stopping too early. Every one of
 * these is a way it has to stop.
 */
class GoalBoardTest {
    private val board = GoalBoard()

    private fun plan(vararg done: Boolean) =
        TaskPlan(done.mapIndexed { index, ticked -> TaskStep("step $index", ticked) })

    @Test
    fun `a goal runs until every step is ticked`() {
        board.start("Pull the dates out of my notes")
        assertThat(board.isRunning).isTrue()
        board.planned(plan(false, false))

        board.advanced(plan(true, false))
        assertThat(board.goal.value?.state).isEqualTo(GoalState.WORKING)

        board.advanced(plan(true, true))
        assertThat(board.goal.value?.state).isEqualTo(GoalState.DONE)
        assertThat(board.isRunning).isFalse()
    }

    @Test
    fun `the step budget stops a plan that never finishes`() {
        board.start("Something open ended")
        board.planned(plan(false, false))

        // A model that never ticks anything, which is the shape this budget exists for.
        repeat(Goal.MAX_STEPS) { board.advanced(plan(false, false)) }

        assertThat(board.goal.value?.state).isEqualTo(GoalState.HALTED)
        assertThat(board.goal.value?.note).contains("${Goal.MAX_STEPS} steps")
        assertThat(board.isRunning).isFalse()
    }

    @Test
    fun `stop is always allowed and needs no reason`() {
        board.start("Anything")
        board.planned(plan(false, false))
        board.stop()

        assertThat(board.goal.value?.state).isEqualTo(GoalState.STOPPED)
        assertThat(board.isRunning).isFalse()
    }

    @Test
    fun `heat or battery halts it and says which`() {
        board.start("Anything")
        board.halt("Paused: the phone is too hot to keep going")

        assertThat(board.goal.value?.state).isEqualTo(GoalState.HALTED)
        assertThat(board.goal.value?.note).contains("too hot")
    }

    @Test
    fun `steering is held for the next step rather than dropped or acted on at once`() {
        board.start("Anything")
        board.steer("actually only the ones from September")
        board.steer("  ")
        board.steer("and put them in a table")

        // Blank input is not steering.
        assertThat(board.steering.value).hasSize(2)

        val taken = board.takeSteering()
        assertThat(taken).containsExactly(
            "actually only the ones from September",
            "and put them in a table",
        ).inOrder()
        // Taken once, so the step after this one does not read it again.
        assertThat(board.steering.value).isEmpty()
    }

    @Test
    fun `a new goal starts with nothing left over from the last one`() {
        board.start("First")
        board.steer("something")
        board.start("Second")

        assertThat(board.goal.value?.task).isEqualTo("Second")
        assertThat(board.goal.value?.stepsTaken).isEqualTo(0)
        assertThat(board.steering.value).isEmpty()
    }

    @Test
    fun `clearing leaves nothing behind for the next conversation`() {
        board.start("First")
        board.steer("something")
        board.clear()

        assertThat(board.goal.value).isNull()
        assertThat(board.isRunning).isFalse()
        assertThat(board.steering.value).isEmpty()
    }
}

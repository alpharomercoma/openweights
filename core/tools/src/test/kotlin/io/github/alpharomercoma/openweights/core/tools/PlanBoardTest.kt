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
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The plan the app holds, and the one tool that moves it.
 *
 * What is being protected here is that the model never rewrites the list. It names a number,
 * and everything else about the plan is the app's. A model that names the wrong number, no
 * number, or a number that is not there has to leave the plan intact, because those are the
 * three things a 1B model does and none of them should cost the user their plan.
 */
class PlanBoardTest {
    private val board = PlanBoard()
    private val advance = AdvanceTool(board)

    private fun call(arguments: String) = ToolCall("1", "advance", arguments)

    @Test
    fun `a reply with a list in it becomes the plan`() {
        val kept = board.propose("Here is the plan.\n1. find it\n2. read it\n3. save it")

        assertThat(kept).isTrue()
        assertThat(board.plan.value?.steps).hasSize(3)
    }

    @Test
    fun `a reply that answered instead of planning leaves the board empty`() {
        val kept = board.propose("The capital of France is Paris.")

        assertThat(kept).isFalse()
        assertThat(board.plan.value).isNull()
    }

    @Test
    fun `the tool is invisible until there is a plan and again once it is done`() {
        // The catalogue an ordinary question is routed over must not grow because a feature
        // exists. Every tool costs tokens once and accuracy again.
        assertThat(advance.isAvailable).isFalse()

        board.propose("1. find it\n2. read it")
        assertThat(advance.isAvailable).isTrue()

        board.tick(0)
        board.tick(1)
        assertThat(advance.isAvailable).isFalse()
    }

    @Test
    fun `advancing ticks the step the model named and reports what is left`() = runTest {
        board.propose("1. find it\n2. read it\n3. save it")

        val said = advance.run(call("""{"step": 1}"""))

        assertThat(board.plan.value?.steps?.first()?.done).isTrue()
        // Counted from one on the way in, because that is how the plan is written and how
        // anyone reading it counts.
        assertThat(board.plan.value?.steps?.get(1)?.done).isFalse()
        assertThat(said).contains("read it")
    }

    @Test
    fun `a step number that is not there changes nothing`() = runTest {
        board.propose("1. find it\n2. read it")

        advance.run(call("""{"step": 9}"""))

        assertThat(board.plan.value?.steps?.none { it.done }).isTrue()
    }

    @Test
    fun `no number at all asks for one rather than guessing`() = runTest {
        board.propose("1. find it\n2. read it")

        val said = advance.run(call("""{"note": "the first one"}"""))

        assertThat(said).contains("No step number")
        assertThat(board.plan.value?.steps?.none { it.done }).isTrue()
    }

    @Test
    fun `the last step finished says so instead of pointing at another`() = runTest {
        board.propose("1. find it\n2. read it")
        advance.run(call("""{"step": 1}"""))

        val said = advance.run(call("""{"step": 2}"""))

        assertThat(said).contains("Every step is done")
    }

    @Test
    fun `a new conversation starts with no plan`() {
        board.propose("1. find it\n2. read it")

        board.clear()

        assertThat(board.plan.value).isNull()
    }

    @Test
    fun `a rejected completion restores the plan before the turn`() {
        board.propose("1. search it\n2. write it")
        val before = requireNotNull(board.plan.value)
        board.tick(0)

        board.restore(before)

        assertThat(board.plan.value).isEqualTo(before)
        assertThat(board.plan.value?.steps?.none { it.done }).isTrue()
    }
}

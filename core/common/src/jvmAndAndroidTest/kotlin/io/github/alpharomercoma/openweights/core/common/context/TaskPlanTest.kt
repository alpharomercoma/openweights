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

package io.github.alpharomercoma.openweights.core.common.context

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Reading a plan out of what a small model wrote, and saying it back in as few tokens as
 * possible.
 *
 * Both halves are bounded on purpose. A 1B model asked for a plan writes a paragraph, a
 * numbered list, or a numbered list wrapped in a paragraph, and only the list is a plan. Said
 * back, it has to cost tens of tokens rather than hundreds, because it is read on every pass
 * of every turn from here on and a window of two thousand cannot afford a second opinion.
 */
class TaskPlanTest {
    @Test
    fun `a numbered list is a plan`() {
        val wrote = """
            Here is what I would do.
            1. Find the notes file
            2. Read the part about the budget
            3. Save a summary next to it
        """.trimIndent()

        val plan = readPlan(wrote)

        assertThat(plan?.steps?.map { it.text }).containsExactly(
            "Find the notes file",
            "Read the part about the budget",
            "Save a summary next to it",
        ).inOrder()
    }

    @Test
    fun `bullets and other numbering are read the same way`() {
        val bullets = readPlan("- open the file\n- read it\n* write it back")
        val parens = readPlan("1) open the file\n2) read it\n3) write it back")

        assertThat(bullets?.steps).hasSize(3)
        assertThat(parens?.steps).hasSize(3)
    }

    @Test
    fun `prose with no list in it is not a plan`() {
        // The common failure, and the reason this returns null rather than guessing: a model
        // that answered the question instead of planning has not written steps, and inventing
        // some from its sentences would put a plan on screen that it never proposed.
        assertThat(readPlan("I would search the web and then tell you what I found.")).isNull()
    }

    @Test
    fun `one step is not a plan`() {
        assertThat(readPlan("1. Answer the question")).isNull()
    }

    @Test
    fun `a long list is cut to what a small window can carry`() {
        val many = (1..12).joinToString("\n") { "$it. step number $it" }

        val plan = readPlan(many)

        assertThat(plan?.steps).hasSize(TaskPlan.MAX_STEPS)
    }

    @Test
    fun `a step longer than a line is trimmed rather than dropped`() {
        val wordy = "1. " + "go on and on ".repeat(20) + "\n2. stop"

        val step = readPlan(wordy)?.steps?.first()

        assertThat(step?.text?.length).isAtMost(TaskPlan.MAX_STEP_CHARS)
        assertThat(step?.text).startsWith("go on and on")
    }

    @Test
    fun `the status block is short and says which step is next`() {
        val plan = readPlan("1. find it\n2. read it\n3. save it")!!.ticked(0)

        val block = plan.statusBlock()

        assertThat(block).contains("find it")
        assertThat(block).contains("read it")
        // Done and not done have to be tellable apart, or the block says nothing.
        assertThat(block.lines().first { it.contains("find it") }).contains("x")
        assertThat(block.lines().first { it.contains("read it") }).doesNotContain("x")
        // Small enough to send on every pass: four characters to a token puts this well
        // under a hundred, against the several hundred a model rewriting the list would cost.
        assertThat(block.length / CHARS_PER_TOKEN).isLessThan(100)
    }

    @Test
    fun `ticking is by position and survives being ticked twice`() {
        val plan = readPlan("1. find it\n2. read it\n3. save it")!!

        assertThat(plan.ticked(1).ticked(1).steps.count { it.done }).isEqualTo(1)
        assertThat(plan.ticked(1).steps[1].done).isTrue()
        // Out of range is a model naming a step that is not there, which is a thing they do.
        assertThat(plan.ticked(9).steps.none { it.done }).isTrue()
    }

    @Test
    fun `a finished plan says so rather than asking for a next step`() {
        val plan = readPlan("1. find it\n2. read it")!!.ticked(0).ticked(1)

        assertThat(plan.isFinished).isTrue()
        assertThat(plan.statusBlock()).doesNotContain("Do the next")
    }

    private companion object {
        const val CHARS_PER_TOKEN = 4
    }
}

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

/** One thing the model said it would do, and whether it has been done yet. */
data class TaskStep(val text: String, val done: Boolean = false)

/**
 * The short list of steps a plan-mode turn proposed, owned by the app rather than the model.
 *
 * Who owns it is the whole design. Claude Code has the model write the list and rewrite it as
 * it goes, which works because a frontier model can hold a plan and a hundred thousand tokens
 * of context at once. Costed against a two thousand token window it does not: a five step
 * list with its reasoning is about three hundred and fifty tokens, and rewriting it every
 * pass reaches the wall around step three of five.
 *
 * So the model proposes once and the app keeps it. What goes back in is [statusBlock], which
 * is a handful of lines rather than a list to be rewritten, and it goes at the tail of the
 * prompt for two reasons at once: it is the position a small model attends to best, and it is
 * the only place a block that changes every pass can sit without moving the tokens the cache
 * has already read.
 */
data class TaskPlan(val steps: List<TaskStep>) {
    /** True when there is nothing left to do, so the block stops asking for a next step. */
    val isFinished: Boolean get() = steps.isNotEmpty() && steps.all { it.done }

    /** The first thing still to do, which is the only step the model is ever pointed at. */
    val next: TaskStep? get() = steps.firstOrNull { !it.done }

    /**
     * The plan with one step marked done.
     *
     * Out of range is not an error. A model naming a step that does not exist is a thing they
     * do, and it means the same as naming none: nothing changes and the turn goes on.
     */
    fun ticked(index: Int): TaskPlan {
        if (index !in steps.indices) return this
        val marked = steps.mapIndexed { at, step ->
            if (at == index) step.copy(done = true) else step
        }
        return copy(steps = marked)
    }

    /**
     * What the model is told about the plan, which is as little as will do.
     *
     * A box each, because done and not done have to be tellable apart in a form a model
     * already knows from every markdown checklist it was trained on, and one closing line
     * pointing at the next step. Around fifty tokens for five steps.
     */
    fun statusBlock(): String {
        if (steps.isEmpty()) return ""
        val listed = steps.mapIndexed { at, step ->
            "${at + 1}. [${if (step.done) "x" else " "}] ${step.text}"
        }
        val closing = next?.let { "Do the next unticked step, then say what you did." }
        return (listOf("Plan:") + listed + listOfNotNull(closing)).joinToString("\n")
    }

    companion object {
        /**
         * Five, which is what fits and what a 1B model can hold.
         *
         * Past this the block stops being a reminder and starts being a document, and a model
         * that has lost track of five steps will not do better with nine.
         */
        const val MAX_STEPS = 5

        /** A step is a line, not a paragraph: long ones are trimmed rather than dropped. */
        const val MAX_STEP_CHARS = 60

        /** What the whole block may cost, since it is read on every pass from here on. */
        const val MAX_BLOCK_TOKENS = 100
    }
}

/**
 * The plan in what a model wrote, or null if it did not write one.
 *
 * Only a list counts. Asked to plan, a small model writes a paragraph, a numbered list, or a
 * numbered list wrapped in a paragraph, and reading steps out of prose would put a plan on
 * screen that the model never proposed and the user never agreed to. Two steps is the floor,
 * because one step is an answer.
 */
fun readPlan(text: String): TaskPlan? {
    val steps = text.lines()
        .mapNotNull { it.asStep() }
        .take(TaskPlan.MAX_STEPS)
    return if (steps.size < MINIMUM_STEPS) null else TaskPlan(steps)
}

/** A line that opens with a number or a bullet, with the marker taken off. */
private fun String.asStep(): TaskStep? {
    val line = trim()
    val body = STEP_MARKER.find(line)?.let { line.removeRange(it.range) }?.trim()
    return body?.takeIf { it.isNotEmpty() }
        ?.take(TaskPlan.MAX_STEP_CHARS)
        ?.trim()
        ?.let(::TaskStep)
}

/** `1.`, `1)`, `-`, `*`, and `Step 1:`, which between them is what they write. */
private val STEP_MARKER = Regex("""^(?:step\s*)?(?:\d+[.):]|[-*•])\s*""", RegexOption.IGNORE_CASE)

/** One step is an answer with a number in front of it. */
private const val MINIMUM_STEPS = 2

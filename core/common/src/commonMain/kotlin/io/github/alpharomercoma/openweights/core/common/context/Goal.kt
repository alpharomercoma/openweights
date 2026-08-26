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

/**
 * A task the app works through on its own, and the budget it is allowed to spend.
 *
 * ### What this is scoped to, and why it is not "runs for days"
 *
 * The adversarial review of this idea was blunt and it was right. A 2.6B model given dozens
 * of free turns to browse, plan, write scripts and correct itself does not converge; it
 * fills its context with its own output and produces a confident nonsense artefact. What it
 * does do well is a finite batch of similar operations over files that are already here:
 * pull the same three fields out of twenty notes, run one script against a CSV, rename by
 * content.
 *
 * Two measured constraints set the rest. Decode falls from about 25 to about 19 tokens a
 * second over three and a half minutes of sustained work, so a goal is slower per step the
 * longer it runs. And a fold costs a full re-prefill, so a goal that outgrows its context
 * pays seventeen to thirty seconds every few steps.
 *
 * So a goal is bounded by steps rather than by wall clock, every bound is visible, and the
 * thing it is best at is the thing a cloud assistant will not do for free: iteration over
 * the user's own files, where the marginal cost of a token is zero.
 */
data class Goal(
    /** What the user asked for, in their words, kept for the prompt and for the screen. */
    val task: String,
    /** The steps, once the model has proposed them. Empty until it has. */
    val plan: TaskPlan? = null,
    /** Steps finished, including ones that failed and were given up on. */
    val stepsTaken: Int = 0,
    val state: GoalState = GoalState.PLANNING,
    /** Why it stopped, when it stopped for a reason worth showing. */
    val note: String? = null,
    /**
     * The conversation this goal was started from, or null for one recovered from a
     * snapshot written before this field existed.
     *
     * What tells a goal being restored into the same conversation it was interrupted in
     * apart from the app switching to a different one. Without it, reopening any
     * conversation — including the one a recovered goal already belongs to — read as a
     * switch away from it and cleared the very recovery [afterInterruption] had just made
     * visible.
     */
    val conversationId: Long? = null,
) {
    val isRunning: Boolean get() = state == GoalState.PLANNING || state == GoalState.WORKING

    /**
     * Whether there is budget left.
     *
     * A step cap rather than a time limit, because a step is the unit the user can see and
     * a phone's seconds vary by a factor of two with heat. [MAX_STEPS] is deliberately small
     * and deliberately visible: the failure mode of an autonomous loop on a small model is
     * not stopping too early.
     */
    val hasBudget: Boolean get() = stepsTaken < MAX_STEPS

    /** The step to work on, or null when the plan is finished or absent. */
    val currentStep: TaskStep? get() = plan?.steps?.firstOrNull { !it.done }

    companion object {
        /**
         * How many turns one goal may spend.
         *
         * Twelve, which at the measured 17 to 25 seconds a step for tool work is roughly
         * four minutes of phone. Past that the chip is throttling, the context has folded at
         * least once, and on the evidence from the tool suite the model is more likely to be
         * repeating itself than progressing.
         */
        const val MAX_STEPS = 12
    }
}

/** Where a goal is up to. */
enum class GoalState {
    /** Asking the model for a plan. */
    PLANNING,

    /** Working through the plan. */
    WORKING,

    /** Every step ticked. */
    DONE,

    /** The user pressed stop. */
    STOPPED,

    /** The budget, the battery or the heat ran out. See [Goal.note]. */
    HALTED,
}

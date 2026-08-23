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

import io.github.alpharomercoma.openweights.core.common.context.Goal
import io.github.alpharomercoma.openweights.core.common.context.GoalState
import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The goal the app is working on, if any, and the only place its state changes.
 *
 * A singleton rather than view model state for the same reason [PlanBoard] is one: the turn
 * machinery has to read it and the screen has to watch it, and putting it in the view model
 * would make the runner depend on the screen.
 *
 * Everything here is a small synchronous update. The work itself belongs to the runner; this
 * only records where it got to, so that a screen redrawn after a process death, or opened
 * from the notification, shows the truth rather than a guess.
 */
@Singleton
class GoalBoard @Inject constructor() {
    private val current = MutableStateFlow<Goal?>(null)

    val goal: StateFlow<Goal?> = current.asStateFlow()

    /** True while a goal is working, which is what suppresses the ordinary composer. */
    val isRunning: Boolean get() = current.value?.isRunning == true

    /**
     * Anything the user typed while the goal was running, to be read at the next boundary.
     *
     * Steering rather than interrupting. A goal that stopped to read every keystroke would
     * not be autonomous, and one that ignored the user entirely would be a runaway; so what
     * is typed is held here and folded into the next step's prompt, which is the first
     * moment reading it cannot corrupt a turn already in flight.
     */
    private val pending = MutableStateFlow<List<String>>(emptyList())

    val steering: StateFlow<List<String>> = pending.asStateFlow()

    fun start(task: String) {
        pending.value = emptyList()
        current.value = Goal(task = task)
    }

    fun planned(plan: TaskPlan) = current.update {
        it?.copy(plan = plan, state = GoalState.WORKING)
    }

    /** One step finished, whatever became of it. */
    fun advanced(plan: TaskPlan) = current.update { goal ->
        goal ?: return@update null
        val next = goal.copy(plan = plan, stepsTaken = goal.stepsTaken + 1)
        when {
            plan.isFinished -> next.copy(state = GoalState.DONE)
            !next.hasBudget -> next.copy(
                state = GoalState.HALTED,
                note = "Stopped after ${Goal.MAX_STEPS} steps. Say what to do next and it " +
                    "will carry on.",
            )

            else -> next
        }
    }

    /** The user pressed stop, which is always allowed and never needs a reason. */
    fun stop() = current.update { it?.copy(state = GoalState.STOPPED) }

    /** Something outside the goal ended it: heat, battery, or a model that went away. */
    fun halt(why: String) = current.update {
        it?.copy(state = GoalState.HALTED, note = why)
    }

    /** Cleared with the conversation it belonged to. */
    fun clear() {
        current.value = null
        pending.value = emptyList()
    }

    fun steer(message: String) {
        if (message.isBlank()) return
        pending.update { it + message }
    }

    /**
     * Takes what was typed, leaving nothing behind for the step after this one.
     *
     * `getAndUpdate` rather than a read followed by a write, because the two are not the
     * same thing here. Steering arrives from the user's thread while the goal loop reads it
     * between steps, and anything typed in the window between the read and the clear was
     * silently dropped: the one message a person sends to redirect a running goal is exactly
     * the message that arrives while a step is ending.
     */
    fun takeSteering(): List<String> = pending.getAndUpdate { emptyList() }
}

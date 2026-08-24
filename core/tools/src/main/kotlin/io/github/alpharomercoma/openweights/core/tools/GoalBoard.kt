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
class GoalBoard @Inject constructor(private val snapshots: GoalSnapshotStore) {
    /** Lightweight non-persistent board for unit fixtures and previews. */
    constructor() : this(GoalSnapshotStore.none())

    private val lock = Any()
    private val restored = snapshots.load()
    private val current = MutableStateFlow(restored?.goal?.afterInterruption())

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
    private val pending = MutableStateFlow(restored?.steering.orEmpty())

    val steering: StateFlow<List<String>> = pending.asStateFlow()

    init {
        // A process can disappear after a tool made its change but before the turn recorded
        // that it finished. Continuing automatically would repeat that change. Keep the plan
        // and steering, but make the interruption visible and wait for a person to decide.
        current.value?.let { goal -> persist(goal, pending.value) }
    }

    fun start(task: String) {
        synchronized(lock) {
            pending.value = emptyList()
            current.value = Goal(task = task)
            persist(current.value, pending.value)
        }
    }

    fun planned(plan: TaskPlan) = changeGoal {
        it.copy(plan = plan, state = GoalState.WORKING)
    }

    /** One step finished, whatever became of it. */
    fun advanced(plan: TaskPlan) = changeGoal { goal ->
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
    fun stop() = changeGoal { it.copy(state = GoalState.STOPPED) }

    /** Something outside the goal ended it: heat, battery, or a model that went away. */
    fun halt(why: String) = changeGoal { it.copy(state = GoalState.HALTED, note = why) }

    /** Cleared with the conversation it belonged to. */
    fun clear() {
        synchronized(lock) {
            current.value = null
            pending.value = emptyList()
            snapshots.clear()
        }
    }

    fun steer(message: String) {
        if (message.isBlank()) return
        synchronized(lock) {
            // Steering is copied into a later prompt and persisted. Bound both axes here,
            // not only in the UI, because tools and tests can call the board directly and
            // an oversized value must not erase the otherwise valid recovery snapshot.
            pending.value = (pending.value + message.take(MAX_STEERING_MESSAGE_CHARS))
                .takeLast(MAX_STEERING_MESSAGES)
            persist(current.value, pending.value)
        }
    }

    /**
     * Takes what was typed, leaving nothing behind for the step after this one.
     *
     * Taken and persisted under the same lock rather than as a read followed by a write,
     * because the two are not the same thing here. Steering arrives from the user's thread
     * while the goal loop reads it between steps, and anything typed in the window between
     * the read and the clear would be silently dropped: the one message a person sends to
     * redirect a running goal is exactly the message that arrives while a step is ending.
     */
    fun takeSteering(): List<String> = synchronized(lock) {
        val taken = pending.value
        pending.value = emptyList()
        persist(current.value, pending.value)
        taken
    }

    private fun changeGoal(change: (Goal) -> Goal) {
        synchronized(lock) {
            current.value = current.value?.let(change)
            persist(current.value, pending.value)
        }
    }

    private fun persist(goal: Goal?, steering: List<String>) {
        if (goal == null) snapshots.clear() else snapshots.save(GoalSnapshot(goal, steering))
    }

    private fun Goal.afterInterruption(): Goal = if (isRunning) {
        copy(
            state = GoalState.HALTED,
            note = "Interrupted when the app stopped. Review the plan before starting again.",
        )
    } else {
        this
    }

    private companion object {
        const val MAX_STEERING_MESSAGES = 16
        const val MAX_STEERING_MESSAGE_CHARS = 500
    }
}

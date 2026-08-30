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

import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import io.github.alpharomercoma.openweights.core.common.context.readPlan
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The plan the app is holding, if any.
 *
 * The app holds it rather than the model, which is the decision the whole feature rests on.
 * A frontier harness has the model write the list and rewrite it as it goes; costed against
 * a two thousand token window that reaches the wall around step three of five. Here the model
 * proposes once, the app keeps it, and the model moves through it one step at a time with
 * [AdvanceTool]. What goes back into the prompt is [TaskPlan.statusBlock], which is lines
 * rather than a list to be rewritten.
 *
 * A singleton because the plan outlives the turn that proposed it, and because the tool, the
 * screen and the turn loop all have to see the same one.
 */
@Singleton
class PlanBoard @Inject constructor() {
    private val current = MutableStateFlow<TaskPlan?>(null)

    val plan: StateFlow<TaskPlan?> = current.asStateFlow()

    /**
     * Reads a plan out of a reply and keeps it, saying whether there was one.
     *
     * False for a model that answered rather than planned, which is most of them most of the
     * time. Nothing is kept in that case: a plan on screen that the model never proposed is
     * worse than no plan.
     */
    fun propose(reply: String): Boolean {
        val found = readPlan(reply) ?: return false
        current.value = found
        return true
    }

    /**
     * Marks one step done, by its position in the list as the user and model both see it.
     *
     * [MutableStateFlow.update] rather than read, modify and assign. Two things tick this and
     * they are on different threads: the person tapping a box, and [AdvanceTool] running
     * wherever the turn runs. Written as an assignment, two ticks that overlap keep only one,
     * and the box the user pressed comes back unticked.
     */
    fun tick(step: Int) = current.update { it?.ticked(step) }

    /**
     * Restores the plan held before a turn whose claimed completion was rejected by the host.
     *
     * Research verifies that a search actually ran after the model answers. If the model
     * called [AdvanceTool] without doing that work, its tick must be rolled back before the
     * step is retried; otherwise the retry silently moves to the next question.
     */
    fun restore(plan: TaskPlan) {
        current.value = plan
    }

    /** Dropped when the conversation is, or when the user starts a new one. */
    fun clear() {
        current.value = null
    }
}

/**
 * Moves the plan on by one step.
 *
 * One integer argument and nothing else, which is the point. Rewriting the list is where both
 * the token cost and the error rate live at this size: a model asked to reproduce five lines
 * will drop one, reorder two, or quietly mark something done that is not. Naming a number is
 * a thing a 1B model can do.
 *
 * It describes itself only while a plan is open, so an ordinary question never sees it and
 * the catalogue a routing decision is made over is unchanged.
 */
@Singleton
class AdvanceTool @Inject constructor(private val board: PlanBoard) : Tool {
    override val definition = ToolDefinition(
        name = NAME,
        description = "Mark one step of the plan finished, by its number.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "step": {
                  "type": "integer",
                  "description": "The number of the step that is now done, as the plan lists it"
                }
              },
              "required": ["step"]
            }
        """.trimIndent(),
    )

    override val isUserFacing: Boolean = false

    override val isAvailable: Boolean
        get() = board.plan.value?.isFinished == false

    override val needsApproval: Boolean = false

    /** Ticking a box is the plan being read, not something the plan did. */
    override val runsWhilePlanning: Boolean = true

    override val chains: Boolean = true

    companion object {
        /** Named once, because the turn loop has to be able to withhold it by name. */
        const val NAME = "advance"
    }

    override suspend fun run(call: ToolCall): String {
        val step = call.argument("step", "number", "index")?.trim()?.toIntOrNull()
            ?: return "No step number was given. Call advance again with the step's number."
        // One-based on the way in, because that is how the plan is written and how the person
        // reading it counts. Nothing else in this file knows about the offset.
        board.tick(step - 1)
        val plan = board.plan.value ?: return "There is no plan to advance."
        return if (plan.isFinished) {
            "Every step is done. Tell the user what happened."
        } else {
            plan.statusBlock()
        }
    }
}

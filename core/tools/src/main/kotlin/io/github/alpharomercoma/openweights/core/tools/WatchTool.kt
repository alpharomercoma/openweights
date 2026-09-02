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

import io.github.alpharomercoma.openweights.core.common.context.Watch
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition

/**
 * What a watch needs from whoever stores it, so this module does not need a database.
 *
 * `core:tools` deliberately owns no storage, and a tool that reached for Room would drag the
 * whole data layer in behind it. The app supplies this.
 */
fun interface Watches {
    /** Starts a watch, or returns null when there are already [Watch.MAX_ACTIVE]. */
    suspend fun start(task: String, everyMinutes: Int): Watch?
}

/**
 * Lets the model set something up to be checked again later.
 *
 * The one tool here whose effect continues after the conversation ends, which is why it asks
 * first. "Tell me if the price drops" is a reasonable thing to say to an assistant and an
 * unreasonable thing for an assistant to arrange silently: it costs battery on a schedule for
 * as long as it runs, and the user is the one paying.
 */
class WatchTool(private val watches: Watches) : Tool {
    // The task parameter used to teach the rephrasing by example: "'Remind me to stretch'
    // becomes 'Time to stretch'". Caught on-device: pushed with a bare "go" after an
    // apology about an unrelated question, the model called this tool with that example
    // copied out of the schema verbatim, task and all. A worked example in a parameter
    // description is a prototype the model can complete, and for a 1B model a prototype
    // outweighs every instruction around it — so the intent is kept and the example is not.
    override val definition = ToolDefinition(
        name = NAME,
        description = "Check something again on a schedule, for a while: when they ask " +
            "to be told about a change, or to look again every so often. Not for " +
            "anything you can answer now. Stops itself after ${Watch.MAX_RUNS} checks " +
            "or ${Watch.MAX_LIFETIME_HOURS} hours.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "task": {
                  "type": "string",
                  "description": "The condition or fact to check on each scheduled run, phrased as the check itself rather than as a request to the assistant."
                },
                "every_minutes": {
                  "type": "integer",
                  "description": "Minutes between checks, ${Watch.MIN_MINUTES} to ${Watch.MAX_MINUTES}"
                }
              },
              "required": ["task", "every_minutes"]
            }
        """.trimIndent(),
    )

    /** Nothing that keeps running afterwards should start without being seen. */
    override val needsApproval: Boolean = true

    /** It schedules unattended work, so it asks in AUTO too. See [Tool.alwaysAsks]. */
    override val alwaysAsks: Boolean = true

    override suspend fun run(call: ToolCall): String = execute(call).text

    override suspend fun execute(call: ToolCall): ToolExecution {
        val task = call.argument("task", "what", "check")
        val minutes = call.intArgument("every_minutes", "minutes", "interval")
        refusal(task, minutes)?.let { return ToolExecution.rejected(it) }

        // Checked here rather than forced with `!!`. [refusal] already returns non-null
        // whenever either of these is missing, so both are known good, but the compiler
        // cannot see that through a function boundary and `!!` asks a reader to take it on
        // trust. Folding both into one branch keeps that safety without a third exit.
        if (task == null || minutes == null) return ToolExecution.rejected(NO_TASK)

        val started = watches.start(task, minutes)
            ?: return ToolExecution.rejected(
                "There are already ${Watch.MAX_ACTIVE} checks running, which is the " +
                    "limit. Ask the user to stop one first.",
            )

        // Says what will actually happen rather than what was asked for, and says the end
        // out loud. A watch faster than the scheduler's floor keeps a notification up, and
        // the user is entitled to hear both that and the fact that this stops on its own —
        // from the assistant, in the reply, rather than by going looking for the screen.
        val ends = "It stops itself after ${Watch.MAX_RUNS} checks or " +
            "${Watch.MAX_LIFETIME_HOURS} hours, whichever comes first."
        return ToolExecution(
            if (started.needsForegroundService) {
                "Checking every $minutes minutes, with a notification showing while it " +
                    "runs. $ends"
            } else {
                "Checking every $minutes minutes. $ends"
            },
        )
    }

    /**
     * Why this call cannot become a watch, in a sentence the model can act on.
     *
     * Separated from [run] so that the arguments are checked in one place and the sentence
     * says what to do next rather than only what was wrong. A model that is told "no
     * interval was given" calls again with one; one told "invalid arguments" apologises.
     */
    private fun refusal(task: String?, minutes: Int?): String? = when {
        task.isNullOrBlank() -> NO_TASK
        minutes == null -> NO_INTERVAL
        minutes !in Watch.MIN_MINUTES..Watch.MAX_MINUTES ->
            "That interval is outside what this can do. Use between ${Watch.MIN_MINUTES} " +
                "and ${Watch.MAX_MINUTES} minutes."
        else -> null
    }

    companion object {
        const val NAME = "watch"

        private const val NO_TASK = "No task was given. Call watch again saying what to check."
        private const val NO_INTERVAL =
            "No interval was given. Call watch again with every_minutes."
    }
}

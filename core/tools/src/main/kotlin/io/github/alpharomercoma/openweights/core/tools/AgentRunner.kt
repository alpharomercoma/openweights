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

import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import kotlinx.coroutines.CancellationException

/** One step the agent took, as the transcript shows it. */
sealed interface AgentStep {
    /** A tool the model asked for, before it has run. */
    data class Requested(val call: ToolCall) : AgentStep

    /** A tool that ran, with what it returned. */
    data class Ran(val call: ToolCall, val result: String, val millis: Long) : AgentStep

    /** A tool the user declined, or that plan mode refused to run. */
    data class Skipped(val call: ToolCall, val why: String) : AgentStep
}

/** The call a step belongs to, whatever became of it. */
internal fun AgentStep.callId(): String = when (this) {
    is AgentStep.Requested -> call.id
    is AgentStep.Ran -> call.id
    is AgentStep.Skipped -> call.id
}

/** What the model is told about this step. */
internal fun AgentStep.report(): String = when (this) {
    is AgentStep.Requested -> "pending"
    is AgentStep.Ran -> result
    is AgentStep.Skipped -> why
}

/** What the runner decided to do next. */
sealed interface AgentDecision {
    /** Nothing was asked for. The turn is over. */
    data object Finished : AgentDecision

    /** These calls ran, and the conversation now has their results appended. */
    data class Continue(val messages: List<ChatMessage>, val steps: List<AgentStep>) :
        AgentDecision

    /** The model kept asking past the limit. The turn stops with what it has. */
    data class Exhausted(val steps: List<AgentStep>) : AgentDecision
}

/**
 * Runs the tools a model asked for, and says whether the turn should continue.
 *
 * Deliberately not a loop: the loop belongs to whatever owns cancellation and the
 * transcript, and hiding one here would mean a runner that cannot be stopped halfway. This
 * decides one round, which is the part worth testing on its own.
 *
 * @param approve asked once per call in [AgentMode.ASK]. Returning false skips that call
 * and tells the model why, so it can answer without the tool rather than stall.
 */
class AgentRunner(
    private val registry: ToolRegistry,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
) {
    suspend fun step(
        calls: List<ToolCall>,
        round: Int,
        mode: AgentMode,
        approve: suspend (ToolCall) -> Boolean,
        now: () -> Long = System::currentTimeMillis,
    ): AgentDecision {
        if (calls.isEmpty()) return AgentDecision.Finished
        if (mode == AgentMode.PLAN) {
            return AgentDecision.Continue(
                messages = emptyList(),
                steps = calls.map { AgentStep.Skipped(it, "plan mode: nothing was run") },
            )
        }
        // Counted in rounds rather than in calls, because a model that asks for three
        // things at once has not looped, it has been efficient.
        if (round >= maxRounds) {
            return AgentDecision.Exhausted(
                calls.map { AgentStep.Skipped(it, "stopped after $maxRounds rounds of tools") },
            )
        }

        val steps = calls.map { call -> runOne(call, mode, approve, now) }
        // Every step answers, including the ones that did not run: a model told nothing
        // about a call it made has no way to finish the turn.
        val messages = steps.map { ChatMessage.toolResult(it.callId(), it.report()) }
        return AgentDecision.Continue(messages, steps)
    }

    private suspend fun runOne(
        call: ToolCall,
        mode: AgentMode,
        approve: suspend (ToolCall) -> Boolean,
        now: () -> Long,
    ): AgentStep {
        val tool = registry.find(call.name)
            ?: return AgentStep.Skipped(
                call,
                "There is no tool called ${call.name}. " +
                    "Available: ${registry.definitions.joinToString { it.name }}.",
            )

        val autoAllows = mode == AgentMode.AUTO && !tool.alwaysAsk
        val allowed = autoAllows || !tool.needsApproval || approve(call)
        if (!allowed) {
            return AgentStep.Skipped(call, "The user declined to run ${call.name}.")
        }

        val startedAt = now()
        // A tool that throws must not end the turn: the model is told what went wrong and
        // can try something else, which is the whole point of a tool loop.
        //
        // Cancellation is the exception, and it has to be rethrown. runCatching swallows
        // it like anything else, which turned Stop pressed during a slow request into a
        // tool failure the agent then carried on from: the turn kept going after the user
        // had ended it.
        val result = try {
            tool.run(call)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            "${call.name} failed: ${failure.message ?: "unknown error"}"
        }
        return AgentStep.Ran(call, result, now() - startedAt)
    }

    companion object {
        /**
         * How many rounds of tools one question may take.
         *
         * Every round is a full prefill of a growing conversation on a phone, so this is a
         * budget of seconds as much as of steps: two rounds is search then answer, which
         * is what a question needs when it needs anything.
         *
         * It was four, which measured at five minutes for "gojo vs sukuna": search, read a
         * page, search again, read again, each round adding thousands of characters to a
         * prompt that then had to be re-read. A hosted assistant answers that from memory
         * in seconds. The budget is not what makes an agent good; knowing it does not need
         * one is, and a small ceiling forces the question.
         */
        const val DEFAULT_MAX_ROUNDS = 2
    }
}

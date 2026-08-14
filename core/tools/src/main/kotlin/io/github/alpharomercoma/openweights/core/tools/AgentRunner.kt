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
import kotlinx.serialization.json.Json

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

/**
 * What makes two calls the same call.
 *
 * The arguments are canonicalised through the parser when they will go through it, so
 * `{"query":"x"}` and `{"query": "x"}` are one call rather than two. When they will not,
 * which for a model this size is often, the text stands as written: the alternative is
 * stripping whitespace out of the string, and that would fold a search for "new york" into
 * one for "newyork". Missing a repeat costs a round; inventing one refuses a call the model
 * meant, so the doubt goes that way.
 */
internal fun ToolCall.settledKey(): String {
    val canonical = runCatching { Json.parseToJsonElement(argumentsJson).toString() }
        .getOrDefault(argumentsJson.trim())
    return "$name($canonical)"
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
    /**
     * Whether text somebody else wrote has reached the model during this turn.
     *
     * State on the instance, and the instance is one turn: [TurnRunner] builds a runner per
     * turn, so this resets when the turn does. That is the right scope. A file read an hour
     * ago is not in the window any more, and holding the suspicion across a whole
     * conversation would ask about every search anyone ever made afterwards.
     */
    private var readUntrustedText = false

    /**
     * Calls this turn has already answered, and what to say if one is asked for again.
     *
     * A small model that has just been handed a tool result frequently asks for the same
     * thing again, word for word: it is the shape most recently in front of it, and repeating
     * a shape is what these models are best at. Each repeat costs a full prefill of a
     * conversation that has grown, and on the round budget here it costs the round in which
     * the answer was going to be written.
     *
     * Both halves are keyed the same way, on the name and the arguments together, because
     * both are claims about one exact call. A search for something else is a new call; the
     * same search twice is a loop. And a tool the user declined is only settled for the
     * arguments they saw: declining one address is not declining the tool, and treating it
     * that way would take the decision away from the person who made it.
     *
     * Per turn, like [readUntrustedText] and for the same reason: this instance is one turn,
     * so the next question starts with nothing settled.
     */
    private val settled = mutableMapOf<String, String>()

    suspend fun step(
        calls: List<ToolCall>,
        round: Int,
        mode: AgentMode,
        approve: suspend (ToolCall) -> Boolean,
        now: () -> Long = System::currentTimeMillis,
    ): AgentDecision {
        if (calls.isEmpty()) return AgentDecision.Finished
        if (mode == AgentMode.PLAN) {
            // Answered, not merely refused. Plan mode tells the model not to call anything
            // and small models call anyway, and a turn that stops on the refusal ends on
            // whatever fragment preceded the call rather than on a plan. Telling it the
            // call did not run is what buys the pass in which it writes one.
            val steps = calls.map { AgentStep.Skipped(it, "plan mode: nothing was run") }
            return AgentDecision.Continue(
                messages = steps.map { ChatMessage.toolResult(it.callId(), it.report()) },
                steps = steps,
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
        // Before the tool is even looked up, because the cheapest round trip is the one that
        // does not happen and the answer here does not depend on the tool.
        settled[call.settledKey()]?.let { return AgentStep.Skipped(call, it) }

        val tool = registry.find(call.name)
            ?: return AgentStep.Skipped(
                call,
                "There is no tool called ${call.name}. " +
                    "Available: ${registry.definitions.joinToString { it.name }}.",
            )

        if (!allowed(tool, call, mode, approve)) {
            // Written to read sensibly twice over: it goes back to the model as this call's
            // result, and onto the chip in the transcript that already says which tool and
            // that it was skipped.
            settled[call.settledKey()] = "Already declined by the user, with these same " +
                "arguments. Answer without it."
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
        var failed = false
        val result = try {
            tool.run(call)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            failed = true
            "${call.name} failed: ${failure.message ?: "unknown error"}"
        }
        // Set after the run rather than before it, so a tool that failed to read anything
        // does not spend the turn's freedom on text that never arrived.
        if (tool.returnsUntrustedText) readUntrustedText = true
        // Pointed at rather than repeated. The result is already in the conversation as a
        // tool message, so sending it a second time would spend the context twice over to
        // tell the model something it can see.
        //
        // Only a call that got somewhere is settled. A socket that went away is the one case
        // where asking again for exactly the same thing is the right move, and a breaker that
        // caught it would turn a blip into the end of the turn.
        if (!failed) {
            settled[call.settledKey()] = "Already run this turn with these same arguments. " +
                "Its result is above; answer from that rather than calling it again."
        }
        return AgentStep.Ran(call, result, now() - startedAt)
    }

    /**
     * Whether this call runs, asking the user if the mode and the tool both say to.
     *
     * Auto is about removing pointless taps. A tool that would carry off the device text that
     * something else wrote is not a pointless tap, and until a file has actually been read
     * there is nothing for it to carry, so the question only ever arises on the one boundary
     * where it means something.
     */
    private suspend fun allowed(
        tool: Tool,
        call: ToolCall,
        mode: AgentMode,
        approve: suspend (ToolCall) -> Boolean,
    ): Boolean {
        val carriesSomebodyElsesText = readUntrustedText && tool.leavesTheDevice
        val autoAllows = mode == AgentMode.AUTO && !tool.alwaysAsk && !carriesSomebodyElsesText
        return autoAllows || !tool.needsApproval || approve(call)
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

        /**
         * The ceiling for a turn whose tools are steps rather than errands.
         *
         * Find the file, read it, write it: three rounds before a word reaches the user, so
         * two refuses the one that saves the work. Four leaves a single round of slack for
         * the model to look twice, and no more, because the argument above still holds and
         * every extra round re-reads a prompt that has grown.
         */
        const val CHAINED_MAX_ROUNDS = 4
    }
}

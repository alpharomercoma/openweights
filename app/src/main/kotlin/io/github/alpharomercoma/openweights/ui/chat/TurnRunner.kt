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

package io.github.alpharomercoma.openweights.ui.chat

import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.tools.AgentDecision
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentRunner
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

/** What the screen needs to be told while a turn runs. */
interface TurnListener {
    /** Called at the coalescing rate with everything produced so far in this pass. */
    fun onText(raw: String)

    /** One pass finished. Called once per pass, so a turn with tools calls it twice or more. */
    fun onPass(event: GenerationEvent.Completed, raw: String)

    /** Tools ran, were declined, or were refused. */
    fun onSteps(steps: List<AgentStep>)

    /** A new pass is about to start, so the entry should be cleared for it. */
    fun onNextPass()

    /** Asks the user about one tool. Only reached in [AgentMode.ASK]. */
    suspend fun onApproval(call: ToolCall): Boolean
}

/**
 * One turn, from the question to the final answer, tools and all.
 *
 * Extracted from the view model rather than living in it. The view model already owns the
 * engine's lifecycle, the transcript, storage, compaction and the conversation drawer, and
 * three separate static checks said so before this loop was added to it. What is here is
 * only the loop: stream, see what was asked for, run it, stream again.
 *
 * The loop is here rather than in [AgentRunner] because it has to be cancellable, and
 * cancellation belongs to whoever owns the coroutine. [AgentRunner] decides one round and
 * is tested on its own.
 */
@Singleton
class TurnRunner @Inject constructor(
    private val engine: InferenceEngine,
    private val tools: ToolRegistry,
) {
    private val agent = AgentRunner(tools)

    /**
     * Runs until the model stops asking for tools, or the budget runs out.
     *
     * @return the raw text of the last pass, which is what a cancellation should keep.
     */
    suspend fun run(
        conversation: List<ChatMessage>,
        params: SamplerParams,
        mode: AgentMode,
        listener: TurnListener,
    ): String {
        var messages = conversation
        var round = 0
        var lastRaw = ""

        while (true) {
            val offerTools = round < AgentRunner.DEFAULT_MAX_ROUNDS
            val pass = streamOnce(messages, params, offerTools, listener) { lastRaw = it }
                ?: return lastRaw

            val calls = pass.event.toolCalls
            if (calls.isEmpty()) return lastRaw

            val decision = agent.step(calls, round, mode, listener::onApproval)
            listener.onSteps(decision.steps())

            val results = (decision as? AgentDecision.Continue)?.messages.orEmpty()
            if (results.isEmpty()) return lastRaw

            // The assistant turn that asked goes back too, or the model is handed results
            // for a question it cannot see itself having asked.
            messages = messages + ChatMessage.text(ChatRole.ASSISTANT, pass.raw) + results
            round++
            listener.onNextPass()
        }
    }

    private class Pass(val raw: String, val event: GenerationEvent.Completed)

    private suspend fun streamOnce(
        messages: List<ChatMessage>,
        params: SamplerParams,
        offerTools: Boolean,
        listener: TurnListener,
        publishRaw: (String) -> Unit,
    ): Pass? {
        val reply = StringBuilder()
        var completed: GenerationEvent.Completed? = null

        engine.chat(
            messages = messages,
            params = params,
            // Offered even in plan mode: a plan that cannot name the tools it would use is
            // not a plan. Withdrawn on the last pass, so a model that has used its whole
            // budget is made to answer from what it collected rather than asking again and
            // leaving the user with tool syntax and no reply.
            tools = if (offerTools) tools.definitions else emptyList(),
        ).collect { event ->
            when (event) {
                is GenerationEvent.Token -> {
                    reply.append(event.text)
                    publishRaw(reply.toString())
                    listener.onText(reply.toString())
                }

                is GenerationEvent.Completed -> {
                    listener.onPass(event, reply.toString())
                    completed = event
                }
            }
        }
        return completed?.let { Pass(reply.toString(), it) }
    }
}

/** The steps of any decision, so the caller does not have to match on the type to show them. */
private fun AgentDecision.steps(): List<AgentStep> = when (this) {
    is AgentDecision.Continue -> steps
    is AgentDecision.Exhausted -> steps
    AgentDecision.Finished -> emptyList()
}

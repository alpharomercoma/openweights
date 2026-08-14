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

import android.util.Log
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.containsToolMarkup
import io.github.alpharomercoma.openweights.core.common.model.withoutToolMarkup
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.StopReason
import io.github.alpharomercoma.openweights.core.tools.AgentDecision
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentRunner
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.ToolPrompting
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
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

    /**
     * What the model said before asking for those tools.
     *
     * Kept because it is the only thing that explains why one call followed another. It
     * arrives already stripped of reasoning, which belongs to the thinking block above.
     */
    fun onIntermediate(text: String)

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
    private val switches: ToolSwitches,
) {

    /**
     * True when at least one tool is switched on.
     *
     * Asked before the turn is built, because the instruction that tells the model it can
     * look things up has to go in or stay out together with the tools themselves. With all
     * of them off it was still going in, so the model was told it could search, could not,
     * and said so.
     */
    fun hasEnabledTools(): Boolean = tools.all.any { switches.isEnabled(it.definition.name) }

    /**
     * Runs until the model stops asking for tools, or the budget runs out.
     *
     * @return the raw text of the last pass, which is what a cancellation should keep.
     */
    suspend fun run(
        conversation: List<ChatMessage>,
        params: SamplerParams,
        mode: AgentMode,
        withTools: Boolean,
        listener: TurnListener,
    ): String {
        // Read once per turn, not once per app start: a tool switched off mid-conversation
        // should be off for the next thing asked, and a registry captured at construction
        // would keep offering it until the process died.
        //
        // Availability is checked in the same breath as the switches, because a tool the user
        // left on but which has nothing to work with should not reach the prompt either. That
        // is a live question rather than a setting: a folder grant can be revoked from
        // Settings between one turn and the next.
        val offered = tools.all.filter { it.isAvailable }.map { it.definition.name }
        val active = tools.enabled(switches.enabled(offered))

        // Two rounds is search then answer, which is the whole shape of a turn that looks
        // something up. A turn that works with files is a different shape: find it, read it,
        // write it, which is three before the model has said anything. At two the last of
        // those is refused and the work is thrown away on the step that mattered.
        val maxRounds = active.roundLimit()
        val agent = AgentRunner(active, maxRounds)

        // Said once per turn, because "why did it not search" has three possible answers
        // and the per-pass line only ever showed the conclusion. withTools is the template
        // and the switches together; active is what survived the switches.
        Log.i(
            "OpenWeights",
            "turn withTools=$withTools tools=${active.all.map { it.definition.name }} mode=$mode",
        )

        // Whether the model's own template will carry the definitions. Two of the three
        // families this app is tested against will not, and say nothing about it: the tools
        // are dropped, the model answers in prose, and the app stops being an agent without
        // anything looking wrong. Those get the definitions in the conversation instead.
        val native = engine.loadedModel?.supportsTools == true
        var messages = conversation.describing(active, needed = withTools && !native)
        var round = 0
        var lastRaw = ""
        // Spent at most once a turn. Re-asking a model that cannot produce the syntax is
        // how a phone spends a minute arriving nowhere.
        var repaired = false

        while (true) {
            // Tools are offered from the first pass, which was tried the other way and was
            // worse. Withholding them was meant to stop a small model searching for things
            // it already knew, with a plain-text line it could write instead when it did not
            // know. Measured against LFM2.5 the line was never written once: asked who a
            // stranger was, the model emitted its own trained call syntax naming a tool that
            // does not exist here, so the turn ended with two unrunnable calls and the user
            // got no answer at all.
            //
            // The same measurement showed the decision itself was never the problem. Asked
            // to compare two characters it reasoned that it knew them and answered; asked
            // about a stranger or this year's phone it went to look. What it needs is not to
            // be talked out of searching, it is the real tool present so that when it does
            // decide to search it calls something that exists.
            val offerTools = withTools &&
                round < maxRounds &&
                ToolBudget(headroomTokens()).hasRoom
            val sampling = params.deciding(offerTools)
            // Only a template that can carry them gets them here. The rest were given the
            // same tools in the system message before the first pass.
            val rendered = offerTools && native
            val pass = streamOnce(messages, sampling, active, rendered, listener) { lastRaw = it }
                ?: return lastRaw

            // A cancelled or truncated pass ends the turn here, whatever it left behind.
            // The engine hands its reply back regardless of why it stopped, so half a tool
            // call written before Stop was pressed still parses into a call, and running it
            // means the turn the user ended goes on to fetch a page. Cancelling the
            // coroutine usually gets there first; usually is not a guarantee.
            if (pass.event.reason != StopReason.END_OF_TURN) return lastRaw

            // Salvage only where a call was invited. It reads a tool's name out of ordinary
            // prose, which is sound when the model was shown that tool and got the syntax
            // wrong, and is not sound otherwise: a model whose template cannot render tools
            // has never been offered one, so "I could use web_search for that" is a remark
            // about what it cannot do. Ungated, that remark reached the network. On the
            // pass after the round limit it was merely noise, a step the user never asked
            // for reported as stopped after two rounds.
            val calls = pass.asked(active, conversation, offerTools, native)
            if (calls.isEmpty()) {
                // Call-shaped text that neither parser could read. For a model this size
                // that is the common case rather than the exceptional one, and the usual
                // mistake is inventing a tool that does not exist, so it is handed back the
                // names that do. One round trip, and it does not count against the tool
                // budget, because nothing was run and nothing was read.
                if (offerTools && !repaired && pass.raw.containsToolMarkup()) {
                    repaired = true
                    messages = messages +
                        ChatMessage.text(ChatRole.ASSISTANT, pass.raw.withoutReasoning()) +
                        ChatMessage.text(ChatRole.USER, repairRequest(active))
                    listener.onNextPass()
                    continue
                }
                return lastRaw
            }

            // Said first, then the steps, so the transcript reads in the order it happened.
            // The parser's content, not the raw stream: raw still carries the call itself,
            // and showing "<|tool_call_start|>[web_search(query='...')]" above a chip that
            // already says web_search is the same thing twice, once unreadably.
            pass.spoken().takeIf { it.isNotEmpty() }?.let(listener::onIntermediate)

            val decision = agent.step(calls, round, mode, listener::onApproval)
            listener.onSteps(decision.steps())

            // Sized here rather than once for the whole turn, and after the pass rather
            // than before it, so what the model has just written is already counted.
            val budget = ToolBudget(headroomTokens())
            val results = (decision as? AgentDecision.Continue)?.messages.orEmpty()
                .map(budget::fit)
            if (results.isEmpty()) return lastRaw

            // The assistant turn that asked goes back too, or the model is handed results
            // for a question it cannot see itself having asked. Its thinking does not:
            // reasoning is scratch work, and replaying it as an assistant turn hands the
            // model a literal <think> block inside its own history, which for a template
            // that opens one itself is nonsense it then tries to continue.
            val asked = ChatMessage.text(ChatRole.ASSISTANT, pass.raw.withoutReasoning())
            messages = messages + asked + results
            round++
            listener.onNextPass()
        }
    }

    /**
     * Tokens of the window still free, as the engine last reported it.
     *
     * Null when no model is loaded, which is a different thing from zero and used to be
     * confused with it: zero headroom fell through to the no-model default and offered a
     * turn four thousand characters of tool output on a context with no room for any.
     *
     * Read from the engine rather than from the screen, and read again every round: the
     * engine updates this after every pass, so it already counts the assistant turns that
     * asked for the tools and the template overhead around them, which nothing here could
     * estimate as well.
     */
    private fun headroomTokens(): Int? {
        val model = engine.loadedModel ?: return null
        return (model.contextSize - model.contextUsed).coerceAtLeast(0)
    }

    internal class Pass(val raw: String, val event: GenerationEvent.Completed)

    /**
     * What to say to a model whose call could not be read.
     *
     * Names the tools rather than describing the format, because the format is the
     * template's job and the name is what a small model gets wrong. Ends by permitting an
     * answer without a tool, so a model that cannot manage the syntax at all still finishes
     * the turn rather than trying again forever.
     */
    private fun repairRequest(active: ToolRegistry): String =
        "That did not read as a tool call. The tools available are " +
            active.definitions.joinToString { it.name } +
            ". Call one of those, or answer without a tool."

    @Suppress("LongParameterList")
    private suspend fun streamOnce(
        messages: List<ChatMessage>,
        params: SamplerParams,
        active: ToolRegistry,
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
            tools = if (offerTools) active.definitions else emptyList(),
        ).collect { event ->
            when (event) {
                is GenerationEvent.Token -> {
                    reply.append(event.text)
                    publishRaw(reply.toString())
                    listener.onText(reply.toString())
                }

                is GenerationEvent.Completed -> {
                    // Counts only. Every "why did it not search" question is answered by
                    // whether tools were offered and how many calls came back, and the
                    // reply itself is the user's conversation, which is never logged.
                    Log.i(
                        "OpenWeights",
                        "pass offered=$offerTools calls=${event.toolCalls.size}",
                    )
                    listener.onPass(event, reply.toString())
                    completed = event
                }
            }
        }
        return completed?.let { Pass(reply.toString(), it) }
    }
}

/**
 * How many rounds a turn gets, which depends on whether its tools are steps or errands.
 *
 * Two is search then answer. Find, read, write is three before a word reaches anybody, so a
 * turn holding a tool that chains gets four and no more.
 */
private fun ToolRegistry.roundLimit(): Int =
    if (all.any { it.chains }) AgentRunner.CHAINED_MAX_ROUNDS else AgentRunner.DEFAULT_MAX_ROUNDS

/**
 * What a pass asked for, from whichever of the three ways it had of asking.
 *
 * In order, because they are not equally trustworthy. The template's own parse is what the
 * model was built to produce. The prompted object is what a model whose template drops tools
 * was asked for instead, and is still a definite shape rather than a guess. Prose salvage is
 * the guess, and it goes last.
 */
private fun TurnRunner.Pass.asked(
    active: ToolRegistry,
    conversation: List<ChatMessage>,
    offerTools: Boolean,
    native: Boolean,
): List<ToolCall> {
    if (!offerTools) return event.toolCalls

    val prompted = if (native) emptyList() else listOfNotNull(ToolPrompting.parse(raw, active))
    return event.toolCalls
        .ifEmpty { prompted }
        .ifEmpty { raw.salvagedCall(active, conversation) }
}

/**
 * The conversation with the tools written into its system message.
 *
 * For a template that drops tool definitions, this is the only place they can go. Added once
 * for the whole turn rather than per pass, which also keeps the rendered prefix identical
 * between passes: the engine reuses its cache by comparing tokens until two differ, and
 * moving this text about would throw that away on every round.
 */
private fun List<ChatMessage>.describing(tools: ToolRegistry, needed: Boolean): List<ChatMessage> {
    val described = if (needed) ToolPrompting.describe(tools.definitions) else ""
    if (described.isEmpty()) return this

    val index = indexOfFirst { it.role == ChatRole.SYSTEM }
    if (index < 0) return listOf(ChatMessage.text(ChatRole.SYSTEM, described)) + this
    return toMutableList().apply {
        set(index, ChatMessage.text(ChatRole.SYSTEM, "${this[index].text}\n\n$described"))
    }
}

/**
 * Sampling for a pass that might choose a tool, which is not sampling for prose.
 *
 * Choosing among tools is an argmax, and the public leaderboards score it greedily for that
 * reason. Measured here on a Snapdragon with Qwen 2.5 1.5B, over twenty four routing
 * decisions with everything else held still: fourteen right at the user's own temperature,
 * eighteen at zero, and slightly faster with it.
 *
 * Only while tools are on the table. The pass that writes the final answer out of what the
 * tools returned has none offered, so it keeps whatever the user set, and prose stays theirs.
 * What this does cost is a direct answer given while tools are available, which comes out
 * greedy: right for a question with one answer, flatter for a haiku. That is the trade, and
 * seventeen points of routing accuracy is worth it.
 */
private fun SamplerParams.deciding(offerTools: Boolean): SamplerParams =
    if (offerTools) copy(temperature = 0f) else this

/**
 * The call a model meant to make, when it named a tool in prose instead of calling it.
 *
 * Small models do this however the prompt is worded: they write the tool's own name, then
 * ask permission. Naming it is the decision; the syntax is the only part they got wrong,
 * so the call is made for them from the question they were asked.
 *
 * Constraining generation to the call grammar was tried first and was worse in both
 * directions: the grammar has to be checked against the whole vocabulary for every token,
 * which cost more than the search it was trying to produce, and a model pushed into a
 * shape it was not going to choose kept generating past the call.
 *
 * Empty unless exactly one call can be built, because two is a model weighing options rather
 * than deciding, and empty for anything with side effects the user should see coming, which
 * is what [Tool.alwaysAsk] already marks.
 *
 * Counted over the calls rather than over the names that appear, so a tool which can build
 * nothing from a question does not take another tool's salvage down with it. A file tool
 * cannot build one: a path or a search pattern is not recoverable from what the user asked,
 * which is why [Tool.callFor] is left unimplemented there, and why naming one in prose is
 * not a second option being weighed.
 *
 * Internal rather than private so the benchmark can count how often this fires and whether it
 * was right to. It is the one part of the routing path that is a guess, so whether it earns
 * its place is a measurement, and a measurement has to be able to call it.
 */
internal fun String.salvagedCall(
    tools: ToolRegistry,
    conversation: List<ChatMessage>,
): List<ToolCall> {
    val question = conversation.lastOrNull { it.role == ChatRole.USER }?.text.orEmpty()
    val salvageable = tools.all
        .filter { contains(it.definition.name, ignoreCase = true) }
        .filterNot { it.alwaysAsk }
        .mapNotNull { it.callFor(question) }
    return listOfNotNull(salvageable.singleOrNull())
}

/** The steps of any decision, so the caller does not have to match on the type to show them. */
private fun AgentDecision.steps(): List<AgentStep> = when (this) {
    is AgentDecision.Continue -> steps
    is AgentDecision.Exhausted -> steps
    AgentDecision.Finished -> emptyList()
}

/**
 * How much of what is left of the context window a turn may spend on what tools returned.
 *
 * A page and three search results are a few thousand characters each, and four rounds of
 * them do not fit in 4096 tokens beside the conversation that asked for them. What that
 * looked like was `llama_decode returned 1`, which is the KV cache saying it has no slot
 * left, arriving after several minutes of work and taking the answer with it.
 *
 * So results are trimmed to what is left rather than sent whole, and once the budget is
 * gone no further tools are offered: the model is made to answer from what it already has,
 * which is a worse answer than it wanted and a far better one than an error.
 *
 * This used to be a share of the whole window, handed out once at the start of a turn, and
 * that is the same error in a subtler form. A conversation twenty turns deep has most of
 * its window already spent, and the fold that would free some does not run until three
 * quarters full and never between passes of one turn: at seventy percent used the turn was
 * still being offered a third of the window on top, which is more than the whole of what
 * was left. The attachment path had already learned this and sizes itself from
 * `contextSize - contextUsed`; this one had not.
 */
private class ToolBudget(headroomTokens: Int?) {
    /**
     * Half of what is free, in characters.
     *
     * The other half is the answer the model still has to write, and the turn that asks
     * for the tools. Four characters to a token is the usual English approximation and is
     * deliberately pessimistic here: overestimating the budget is what produces the error
     * this exists to avoid.
     */
    private var remaining = headroomTokens
        ?.let { (it * CHARS_PER_TOKEN / ANSWER_SHARE).coerceAtLeast(0) }
        ?: DEFAULT_BUDGET

    val hasRoom: Boolean get() = remaining > MINIMUM_USEFUL

    /** The message, shortened to what is left, and the budget reduced by what it took. */
    fun fit(message: ChatMessage): ChatMessage {
        val text = message.text
        if (text.length <= remaining) {
            remaining -= text.length
            return message
        }
        val kept = text.take(remaining.coerceAtLeast(0))
        remaining = 0
        // Said in the text rather than trimmed silently, so the model knows the page ran
        // out rather than believing it read all of it.
        return ChatMessage.text(ChatRole.TOOL, "$kept\n[cut short: no context left]")
            .copy(toolCallId = message.toolCallId)
    }

    private companion object {
        const val CHARS_PER_TOKEN = 4

        /** Results take half of what is free, the answer being written takes the other. */
        const val ANSWER_SHARE = 2
        const val MINIMUM_USEFUL = 400

        /** Used only before a model is loaded, when there is no window to divide. */
        const val DEFAULT_BUDGET = 4_000
    }
}

/**
 * What the model said, as opposed to what it asked for.
 *
 * The engine's parser has already separated the two, so its content is used when there is
 * any. The fallback matters when the native parser did not recognise the format and the
 * Kotlin one found the calls instead: then the raw text is all there is, and it still has
 * the call in it.
 */
private fun TurnRunner.Pass.spoken(): String =
    event.content.ifBlank { raw.withoutReasoning().withoutToolMarkup() }.trim()

/**
 * The reply without its thinking.
 *
 * Only the closing tag is looked for, because templates commonly pre-fill the opening one,
 * so a reply can arrive with a close and no open.
 */
private fun String.withoutReasoning(): String {
    val end = indexOf("</think>")
    return if (end < 0) this else substring(end + "</think>".length).trimStart()
}

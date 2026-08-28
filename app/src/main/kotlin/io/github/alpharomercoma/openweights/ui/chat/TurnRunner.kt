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
import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.assistantHistoryText
import io.github.alpharomercoma.openweights.core.common.model.containsToolMarkup
import io.github.alpharomercoma.openweights.core.common.model.withoutToolMarkup
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.StopReason
import io.github.alpharomercoma.openweights.core.tools.AgentDecision
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentRunner
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.Tool
import io.github.alpharomercoma.openweights.core.tools.ToolNotes
import io.github.alpharomercoma.openweights.core.tools.ToolPrompting
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val plans: PlanBoard,
    private val asks: AskBoard,
) {

    /**
     * The plan the app is holding, for the screen to show and the user to tick.
     *
     * Reached through here rather than injected beside the view model's six other
     * collaborators, because the plan is part of the turn machinery and this already owns it.
     * The view model is at both of detekt's limits for a class and has been since before this
     * feature; adding a seventh constructor parameter to it would be the wrong way to pay.
     */
    /**
     * One turn at a time, whoever is asking.
     *
     * The engine holds one model and one KV cache, so two turns at once is not slow, it is
     * wrong: the second one's prefill lands on the first one's context. Nothing needed this
     * while the only caller was a screen a person is looking at, and then watches arrived,
     * which come due on a timer with no idea what the user is doing.
     *
     * Held here rather than in the engine because this is the boundary a turn has: the
     * engine's own calls are individual decodes, and a lock down there would serialise
     * tokens rather than turns.
     */
    private val engineInUse = Mutex()

    val planning: PlanBoard get() = plans

    /** The question the model is waiting on, for the screen to answer. See [planning]. */
    val asking: AskBoard get() = asks

    /**
     * True when at least one tool is switched on.
     *
     * Asked before the turn is built, because the instruction that tells the model it can
     * look things up has to go in or stay out together with the tools themselves. With all
     * of them off it was still going in, so the model was told it could search, could not,
     * and said so.
     */
    /**
     * The tool of that name, for a caller that has a result and needs to know where it came
     * from. Null for a name no longer registered, which a stored note can outlive.
     */
    fun toolNamed(name: String): Tool? = tools.find(name)

    /** Whether the user has switched memory on, which decides if facts reach the prompt. */
    fun remembers(): Boolean =
        tools.all.any { it.definition.name == "remember" && switches.isEnabled(it) }

    fun hasEnabledTools(): Boolean = tools.all.any { it.isUserFacing && switches.isEnabled(it) }

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
        notes: ToolNotes,
        listener: TurnListener,
        offerAsk: Boolean? = null,
        question: String = "",
    ): String = engineInUse.withLock {
        turn(conversation, params, mode, withTools, notes, listener, offerAsk, question)
    }

    /**
     * Runs only if nothing else is using the engine, and returns null rather than waiting.
     *
     * For a caller whose work has a moment: a watch coming due while the user is mid
     * conversation should record that it was skipped, not join a queue. By the time it
     * reached the front the thing it was checking would have moved on, and every skipped
     * tick would run at once when the user put the phone down.
     */
    suspend fun tryRun(
        conversation: List<ChatMessage>,
        params: SamplerParams,
        mode: AgentMode,
        withTools: Boolean,
        notes: ToolNotes,
        listener: TurnListener,
        offerAsk: Boolean? = null,
        question: String = "",
    ): String? {
        if (!engineInUse.tryLock()) return null
        return try {
            turn(conversation, params, mode, withTools, notes, listener, offerAsk, question)
        } finally {
            engineInUse.unlock()
        }
    }

    private suspend fun turn(
        conversation: List<ChatMessage>,
        params: SamplerParams,
        mode: AgentMode,
        withTools: Boolean,
        notes: ToolNotes,
        listener: TurnListener,
        offerAsk: Boolean? = null,
        question: String = "",
    ): String {
        // Read once per turn, not once per app start: a tool switched off mid-conversation
        // should be off for the next thing asked, and a registry captured at construction
        // would keep offering it until the process died.
        //
        // Availability is checked in the same breath as the switches, because a tool the user
        // left on but which has nothing to work with should not reach the prompt either. That
        // is a live question rather than a setting: a folder grant can be revoked from
        // Settings between one turn and the next.
        // Decided here rather than in the screen's mode callback, because this is the thing
        // that knows the mode and the line below is the thing that reads the answer. Asking a
        // clarifying question is only useful while deciding what to do, and every tool in the
        // catalogue makes the choice between the others harder.
        //
        // [offerAsk] overrides this for the one turn that needs it: a research brief's own
        // planning turn, where the model not recognising its subject is not the ambiguity
        // this tool exists for, and offering it anyway is what let a small model ask who
        // the subject was instead of researching it. Null everywhere else, which is every
        // other turn there is.
        asks.offered = offerAsk ?: (mode == AgentMode.PLAN)
        // The switches only govern what the user was offered a switch for. Plan mode's own
        // two tools are machinery, so they follow availability alone; a stale "off" left in
        // the preferences by the screen that used to list them must not disable them now.
        val offered = tools.all.filter { it.isAvailable }
        val active = tools.enabled(
            offered
                // Plan mode gets the machinery and nothing else.
                //
                // It used to be handed the whole catalogue alongside an instruction not to
                // call any of it, and measured on five ambiguous requests it called one
                // anyway two times in five on the 2.6B and four in five on the 1.2B: a mode
                // that does not do what it says. It also rarely asked, which is the one
                // thing plan mode is for.
                //
                // Writing the exception into the instruction was tried first and made it
                // worse, 2 of 5 down to 1. Taking the tools out of the prompt fixed both at
                // once: questions asked went to 4 of 5 and 2 of 5, and tools run went to
                // none on either model. The wording then made no difference at all, which
                // is the same result the web tools gave. A tool in the prompt is an
                // invitation and an instruction not to accept it is weaker than not making
                // it.
                //
                // The cost is one clarifying question on an unambiguous request in two,
                // measured on the 2.6B. In the mode whose whole purpose is to think before
                // acting, that is the right side to err on.
                .filter { mode != AgentMode.PLAN || !it.isUserFacing }
                .filter { !it.isUserFacing || switches.isEnabled(it) }
                .map { it.definition.name }
                .toSet(),
        )

        // Two rounds is search then answer, which is the whole shape of a turn that looks
        // something up. A turn that works with files is a different shape: find it, read it,
        // write it, which is three before the model has said anything. At two the last of
        // those is refused and the work is thrown away on the step that mattered.
        val maxRounds = active.roundLimit()
        val agent = AgentRunner(
            active,
            maxRounds,
            // The notes put an earlier turn's page back into this question, so the guard that
            // asks before anything leaves the device has to know it is there. Without this the
            // suspicion died with the turn that earned it while the text it was about did not.
            carriesUntrustedText = notes.carriesUntrustedText,
            carriesPrivateData = notes.carriesPrivateData,
        )

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
        // Asked separately, because it is a separate question and the answers differ. A
        // template can describe tools and still refuse to render what one gave back:
        // FunctionGemma is tuned for calling and does exactly that, so gating this on
        // `native` would have left the model it was written for still broken.
        val readsResults = engine.loadedModel?.supportsToolResults == true

        // Costs a few dozen tokens of prefill on round zero, on every turn it applies to, so
        // it only applies where the risk it was written for actually exists: a small model
        // conflating this turn's subject with a *tool note* left over from an earlier one.
        // Withdrawn tools mean nothing was ever going to be looked up and there is nothing to
        // confuse the question with; no notes yet means there is nothing left over to confuse
        // it with either, which is every plain single-turn chat and the first tool call of
        // any conversation. Gating on both is what keeps this paid for by the turns it fixes
        // rather than charged to every turn there is.
        val groundingQuestion = question.takeIf { withTools && notes.notes.isNotEmpty() }

        return Turn(
            active,
            agent,
            maxRounds,
            withTools,
            native,
            readsResults,
            conversation,
            groundingQuestion,
        ).run(params, mode, listener)
    }

    /**
     * The passes of one turn, and the state that only means anything inside one.
     *
     * A type rather than a longer function because there are three ways to go round again,
     * each carrying something the next pass reads: a repair, a withdrawal, or another round
     * of tools. Written as one loop it reached twenty branches and three static checks said
     * so on the same line.
     */
    private inner class Turn(
        private val active: ToolRegistry,
        private val agent: AgentRunner,
        private val maxRounds: Int,
        private val withTools: Boolean,
        private val native: Boolean,
        private val readsResults: Boolean,
        private val conversation: List<ChatMessage>,
        private val question: String?,
    ) {
        /**
         * Whether the engine is handed the schemas, decided once for the whole turn.
         *
         * That it is decided once is the point. The engine reuses its cache by comparing
         * tokens until two differ, and the templates these models ship put the tool block
         * near the front: Qwen inside the first system turn, LFM2 above it. Taking the
         * definitions away on the last pass moves the first difference to the top of the
         * prompt and re-reads everything behind it, measured at 257 tokens of a 578 token
         * turn and growing with the conversation.
         *
         * So the definitions stay and the round limit is said in words instead. Only a model
         * that asks anyway pays the old price, once, in [withdraw].
         */
        private var renderTools = withTools && native && ToolBudget(headroomTokens()).hasRoom

        private var messages = conversation.describing(active, needed = withTools && !native)
        private var round = 0
        private var lastRaw = ""

        /**
         * Spent at most once a turn. Re-asking a model that cannot produce the syntax is how
         * a phone spends a minute arriving nowhere.
         */
        private var repaired = false

        /** The withdrawal, kept as the exception it should always have been. */
        private var withdrawn = false

        suspend fun run(params: SamplerParams, mode: AgentMode, listener: TurnListener): String {
            while (true) {
                // Tools are offered from the first pass, which was tried the other way and
                // was worse. Withholding them was meant to stop a small model searching for
                // things it already knew, with a plain-text line it could write instead when
                // it did not know. Measured against LFM2.5 the line was never written once:
                // asked who a stranger was, the model emitted its own trained call syntax
                // naming a tool that does not exist here, so the turn ended with two
                // unrunnable calls and the user got no answer at all.
                //
                // Whether a call made now would run is a different question from whether the
                // model can see the tools, and sampling follows this one: choosing among
                // tools is an argmax, and a pass that can only write prose keeps whatever
                // temperature the user set.
                val mayCall =
                    withTools && round < maxRounds && ToolBudget(headroomTokens()).hasRoom
                // Pinned for this pass only, never folded into the accumulator: the block
                // changes as steps are ticked, and anything that changes has to sit at the
                // very end or it moves tokens the cache has already read. See [grounding] for
                // why it is round zero only, unlike this one.
                val pass = streamOnce(
                    messages.pinning(plans.plan.value).grounding(question?.takeIf { round == 0 }),
                    params.deciding(mayCall),
                    active,
                    renderTools,
                    listener,
                ) { lastRaw = it } ?: return lastRaw

                // A cancelled or truncated pass ends the turn here, whatever it left behind.
                // The engine hands its reply back regardless of why it stopped, so half a
                // tool call written before Stop was pressed still parses into a call, and
                // running it means the turn the user ended goes on to fetch a page.
                // Cancelling the coroutine usually gets there first; usually is not a
                // guarantee.
                if (pass.event.reason != StopReason.END_OF_TURN) return lastRaw

                // Salvage only where a call was invited. It reads a tool's name out of
                // ordinary prose, which is sound when the model was shown that tool and got
                // the syntax wrong, and is not sound otherwise: a model whose template
                // cannot render tools has never been offered one, so "I could use web_search
                // for that" is a remark about what it cannot do. Ungated, that remark
                // reached the network.
                val calls = pass.asked(active, withTools)
                val again = if (mayCall) {
                    advance(pass, calls, mode, listener)
                } else {
                    withdraw(pass, calls, listener)
                }
                if (!again) return lastRaw
                listener.onNextPass()
            }
        }

        /** A pass whose calls can still run: run them, or spend the one repair. */
        private suspend fun advance(
            pass: Pass,
            calls: List<ToolCall>,
            mode: AgentMode,
            listener: TurnListener,
        ): Boolean {
            if (calls.isEmpty()) return repair(pass)

            // Said first, then the steps, so the transcript reads in the order it happened.
            // The parser's content, not the raw stream: raw still carries the call itself,
            // and showing "<|tool_call_start|>[web_search(query='...')]" above a chip that
            // already says web_search is the same thing twice, once unreadably.
            pass.spoken().takeIf { it.isNotEmpty() }?.let(listener::onIntermediate)

            val decision = agent.step(calls, round, mode, listener::onApproval)
            listener.onSteps(decision.steps())

            // Sized here rather than once for the whole turn, and after the pass rather than
            // before it, so what the model has just written is already counted.
            val budget = ToolBudget(headroomTokens())
            val results = (decision as? AgentDecision.Continue)?.messages.orEmpty()
                .map(budget::fit)
                // Said in the last thing the model reads, because that is the only place a
                // per-pass sentence can go without rewriting the front of the prompt. It
                // replaces taking the definitions away, which said the same thing by
                // removing four hundred tokens from the top of it.
                .let { if (round + 1 >= maxRounds) it.closing(maxRounds) else it }
                // Last, so the closing sentence is inside what gets folded rather than
                // appended to a message the template was about to refuse.
                .spelledOut(readsResults)
            if (results.isEmpty()) return false

            // The asking turn goes back exactly as it was decoded, thinking and call syntax
            // and all, or the model is handed results for a question it cannot see itself
            // having asked and the round pays for the whole conversation again.
            //
            // It used to go back with the reasoning stripped, on the grounds that replaying
            // a literal <think> block into a template that opens one itself was nonsense the
            // model then tried to continue. That was true and it is no longer, because the
            // engine now hands the template `reasoning_content` and `preserve_thinking` and
            // it renders a proper block. What the stripping cost is in
            // `ToolLoopReuseOnDeviceTest`: on an SM8650 the second round of a tool turn read
            // 1,222 tokens in 9,586 ms, and reading the same round as it was decoded read 48
            // in 488. The app allows four rounds, so that is most of half a minute a turn.
            //
            // It is not free. On the closed loop suite, keeping the thinking left completion
            // unchanged at 9 of 10 and grew what the model writes by 44%, which at 23 tokens
            // a second is about six seconds a task against the nine a round this buys back.
            messages = messages +
                ChatMessage.text(ChatRole.ASSISTANT, assistantHistoryText(pass.raw)) +
                results
            round++
            return true
        }

        /**
         * Call-shaped text that neither parser could read.
         *
         * For a model this size that is the common case rather than the exceptional one, and
         * the usual mistake is inventing a tool that does not exist, so it is handed back the
         * names that do. One round trip, and it does not count against the tool budget,
         * because nothing was run and nothing was read.
         */
        private fun repair(pass: Pass): Boolean {
            if (repaired || !pass.raw.invitesRepair(active)) return false
            repaired = true
            messages = messages +
                ChatMessage.text(ChatRole.ASSISTANT, assistantHistoryText(pass.raw)) +
                ChatMessage.text(ChatRole.USER, repairRequest(active))
            return true
        }

        /**
         * The budget is spent, and the model asked anyway.
         *
         * It is still holding its tools, so it needs one pass without them or the turn ends
         * on "let me look that up" and the user gets no reply. This is the withdrawal the
         * loop used to perform on every turn, reached now only by a model that earns it.
         */
        private fun withdraw(pass: Pass, calls: List<ToolCall>, listener: TurnListener): Boolean {
            if (calls.isEmpty() || withdrawn) return false
            withdrawn = true
            renderTools = false
            listener.onSteps(calls.map { AgentStep.Skipped(it, spent(maxRounds)) })
            messages = messages +
                ChatMessage.text(ChatRole.ASSISTANT, assistantHistoryText(pass.raw)) +
                calls.map { ChatMessage.toolResult(it.id, spent(maxRounds)) }
                    .spelledOut(readsResults)
            return true
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

    /**
     * What the model is told once its rounds are gone.
     *
     * Phrased as a fact about the budget and then an instruction, because a small model does
     * better with the reason attached: told only to stop it stops mid-sentence, and told only
     * to answer it looks for another tool first.
     */
    private fun spent(maxRounds: Int): String =
        "That was the last of $maxRounds rounds, so there are no more tool calls. " +
            "Answer the question now from what you already have."

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
 * What a pass asked for, from whichever of the two ways it had of asking.
 *
 * In order, because they are not equally trustworthy. The template's own parse is what the
 * model was built to produce. The prompted object is a definite shape rather than a guess,
 * and it is read whenever the first one came back with nothing.
 *
 * That fallback used to be gated on the template *not* rendering tools, and the gate cost a
 * whole model. Hammer 2.1 renders them, so llama.cpp parsed for the Hermes envelope; its
 * template asks for a bare JSON array, so there was no envelope and the engine found nothing;
 * and being native switched off the one parser that could read what it wrote. Measured on a
 * phone, that scored two correct calls out of three as a refusal. A template that renders
 * tool definitions is not a promise about the syntax the weights will answer in.
 *
 * The gate that matters is [offered], and it stays. A model that was never shown a tool has
 * decided nothing, so call-shaped text in its reply is a remark about what it cannot do.
 */
private fun TurnRunner.Pass.asked(active: ToolRegistry, offered: Boolean): List<ToolCall> {
    if (!offered) return emptyList()

    return event.toolCalls.ifEmpty { listOfNotNull(ToolPrompting.parse(raw, active)) }
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
 * The same results, with the last one carrying word that the tools are finished.
 *
 * Appended to a message that was going in anyway rather than sent as a turn of its own: a
 * user turn after a tool result is a shape several templates refuse to render, and a system
 * turn in the middle is worse. The tail of the last tool result is the one place a sentence
 * can be added without moving anything the cache has already read.
 */
/**
 * The same conversation with tool results in a role this template will actually render.
 *
 * A template that would not carry the tool definitions does not carry their answers either,
 * and the two failures look nothing alike. Dropping the definitions is silent, which is why
 * [ToolPrompting] exists at all; the tool role is not, because several of these templates
 * require the roles to alternate and raise rather than render when they do not. Gemma 3
 * raises "Conversation roles must alternate user/assistant", llama.cpp reports it as
 * "Unable to generate parser for this template", and it reaches the user as a turn that
 * produced nothing: the model asks for a search on nearly every question, the search runs,
 * and the pass that was going to use the result cannot be built at all. A tool ran and no
 * answer came back.
 *
 * So for those models the results go back as the user turn they already effectively were.
 * Nothing is being disguised: the model was told about its tools in prose because its
 * template had nowhere else to put them, it asked in prose, and this is the reply in the
 * same prose. Naming the call keeps it readable as a result rather than as the user
 * suddenly reciting a web page.
 *
 * Neighbours of one role are joined for the reason `asExchange` joins them, which is that
 * two user turns in a row is the same violation from the other end and a model that asks
 * for two things at once would land on it.
 *
 * Gated on the template rather than done for everyone, because a template that does support
 * the tool role pairs each result with the call that asked for it through
 * [ChatMessage.toolCallId], and folding those into prose would throw that pairing away for
 * every model the app has no problem with. The gate is its own probe rather than
 * `supportsTools`: FunctionGemma is tuned for calling, describes its tools happily, and
 * still raises on the result, so the model this was written for is exactly the one that
 * answers yes to the other question.
 */
internal fun List<ChatMessage>.spelledOut(readsResults: Boolean): List<ChatMessage> {
    if (readsResults || none { it.role == ChatRole.TOOL }) return this
    return map { message ->
        if (message.role != ChatRole.TOOL) {
            message
        } else {
            ChatMessage.text(
                ChatRole.USER,
                message.toolCallId.takeIf { it.isNotBlank() }
                    ?.let { "$it returned:\n${message.text}" }
                    ?: message.text,
            )
        }
    }.fold(mutableListOf()) { kept, message ->
        val previous = kept.lastOrNull()
        if (previous != null && previous.role == message.role) {
            kept[kept.lastIndex] = previous.copy(
                parts = previous.parts + MessagePart.Text("\n\n") + message.parts,
            )
        } else {
            kept += message
        }
        kept
    }
}

private fun List<ChatMessage>.closing(maxRounds: Int): List<ChatMessage> {
    val last = lastOrNull() ?: return this
    val told = "${last.text}\n\nThat was the last of $maxRounds rounds, so there are no " +
        "more tool calls. Answer the question now from what you already have."
    return dropLast(1) + ChatMessage.text(ChatRole.TOOL, told).copy(toolCallId = last.toolCallId)
}

/**
 * The conversation with the plan's state stuck to the end of the last thing in it.
 *
 * The tail, and not the system message, for the reason everything volatile goes there: the
 * cached prefix is compared token by token from the front, so a block that changes when a
 * step is ticked would move everything behind it. It is also the position a small model
 * attends to best, which is the same reason Claude Code puts its reminders into user-turn
 * content rather than into the instructions.
 */
private fun List<ChatMessage>.pinning(plan: TaskPlan?): List<ChatMessage> {
    val block = plan?.statusBlock().orEmpty()
    if (block.isEmpty()) return this
    val last = lastOrNull() ?: return this
    return dropLast(1) +
        ChatMessage.text(last.role, "${last.text}\n\n$block").copy(toolCallId = last.toolCallId)
}

/**
 * The conversation with this turn's actual question restated at the tail of the last message.
 *
 * Everything the conversation carries by the middle of a turn -- tool notes from earlier
 * questions, the results of this turn's own earlier rounds -- sits closer to the end of the
 * prompt than the question itself did, which is the one place a small model attends best. That
 * is fine when it is evidence for the question being answered and wrong when it is not: three
 * "who is X" turns in a row left two prior people's calls and results in that position, and a
 * fourth name none of them mentioned was answered from their shape instead of its own, on a
 * device where this was measured directly. See [ToolNotes.render]'s trailer for the other half
 * of this fix; that says the notes are not the question, this says what the question is.
 *
 * Round zero only, not every pass the way [pinning] is. A tool round's own results are the
 * newest thing by the second pass and the same pull would apply to them, and it was tried:
 * measured, it cost the very cache reuse the round trip exists to buy. [messages] never
 * carries this text -- only the transient copy handed to one pass does -- so round one's tail
 * is the question with the block on it, and round two's tail is a tool result message with
 * no block on it in [messages] either. Attaching one there anyway restates the same words on
 * a *different* message than round one sent, and the cache is compared from the front: the
 * divergence lands right after the bare question, at the exact point round one's cache holds
 * this block and round two's prompt does not, discarding the tool call and its results behind
 * it. The turn's subject is decided in round zero or not at all -- every reproduction of the
 * conflation this exists for was a wrong choice made there -- so that is the only round worth
 * paying for.
 *
 * The caller also passes null rather than the question itself whenever there is no risk this
 * fixes: no tools offered, or no tool notes yet for this turn's subject to be conflated with.
 * A turn with neither has nothing in the position this block is meant to correct for, so the
 * block is pure prefill cost with nothing to buy -- and it is paid on the most common turn
 * there is, a plain single-turn question, if it is not gated. See `TurnRunner.turn`'s
 * `groundingQuestion`.
 */
private fun List<ChatMessage>.grounding(question: String?): List<ChatMessage> {
    if (question.isNullOrBlank()) return this
    val last = lastOrNull() ?: return this
    val block = "This turn's question: \"$question\"\n" +
        "Answer that question. If an earlier turn's tool notes or results are not about it, " +
        "they are not evidence for it: treat it as its own subject rather than continuing " +
        "or summarising whatever came right before it."
    return dropLast(1) +
        ChatMessage.text(last.role, "${last.text}\n\n$block").copy(toolCallId = last.toolCallId)
}

/**
 * Whether a reply looks like an attempt at a call that did not come out as one.
 *
 * Two shapes. Call-shaped markup neither parser could read, which is the original case. And a
 * short reply that names a tool and nothing else, which is a model announcing what it is about
 * to do and then not doing it: "Let me look that up with web_search."
 *
 * The second used to be handled by building the call here, from the tool and the user's
 * question. That was measured across two device runs and six models and never once improved a
 * score, while costing one twice: what it caught was not models announcing, it was models
 * mentioning a tool inside an answer they had already finished. Handing the names back and
 * spending a pass gets the same recovery without the app inventing arguments nobody asked for.
 */
private fun String.invitesRepair(tools: ToolRegistry): Boolean {
    if (containsToolMarkup()) return true
    val spoken = withoutReasoning().withoutToolMarkup().trim()
    if (spoken.length > ANNOUNCEMENT_CHARS) return false
    return tools.all.any { spoken.contains(it.definition.name, ignoreCase = true) }
}

/**
 * How long a reply can be and still be an announcement rather than an answer.
 *
 * A hundred and sixty characters is about forty tokens, or two sentences. "Let me look that up
 * with web_search" is twenty four; the replies behind every one of the misfires above were
 * complete answers that mentioned a tool in passing.
 */
private const val ANNOUNCEMENT_CHARS = 160

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

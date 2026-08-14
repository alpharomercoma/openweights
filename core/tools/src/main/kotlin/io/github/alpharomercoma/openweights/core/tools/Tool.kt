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

import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition

/**
 * Something the model can actually run.
 *
 * The definition is what the model is shown; [run] is what happens when it asks. Keeping
 * both on one object means a tool cannot be advertised without being runnable, which is
 * the state the app was in before: tools were described to nobody and executed never.
 *
 * Every tool here reaches the network, reads something already on the device, or works
 * inside one folder the user picked and handed over. None of them execute code, and none
 * reach anything that was not granted. That part is a deliberate ceiling and not a temporary
 * one: an app that runs arbitrary code on a phone at a model's suggestion is a different and
 * much worse product.
 *
 * The line moved once, deliberately. It used to read "none of them write files outside the
 * app", which was true of every tool there was until the file tools arrived. What holds the
 * new position is not a promise but a shape: the folder is chosen in the system's own
 * picker, the grant is revocable from Settings without uninstalling anything, the app asks
 * for no storage permission at all, and writing can only create a file that is not there
 * yet. Nothing here can replace or delete what somebody already had. Moving the line again
 * deserves the same argument set down in the same place.
 */
interface Tool {
    val definition: ToolDefinition

    /** Whether the user has to approve each run in [AgentMode.ASK]. */
    val needsApproval: Boolean get() = true

    /**
     * Whether the user has to approve each run even in [AgentMode.AUTO].
     *
     * For tools whose reach the model chooses rather than the app. Searching one
     * encyclopedia is bounded no matter what the model asks; fetching an address the model
     * composed is not, so that one keeps asking however the mode is set. Auto is about
     * removing pointless taps, not about removing the only check on an open primitive.
     */
    val alwaysAsk: Boolean get() = false

    /**
     * Whether this tool can do anything at all as things stand.
     *
     * False keeps the definition out of the prompt entirely, rather than advertising
     * something whose only possible answer is a refusal. A tool waiting on a folder nobody
     * has chosen costs a couple of hundred tokens of a two thousand token window to describe
     * every pass, and the cost is not only the tokens: picking the right tool gets measurably
     * harder as the list grows, so an unusable entry makes the usable ones worse.
     */
    val isAvailable: Boolean get() = true

    /**
     * Whether this tool is usually one step of several rather than the whole errand.
     *
     * Looking something up is one round and an answer. Working with a file is find it, read
     * it, write it, which is three rounds before the model has said anything to anybody, and
     * a limit set for the first shape refuses the last step of the second one: the round
     * where the work was going to be saved is the round that gets thrown away.
     */
    val chains: Boolean get() = false

    /**
     * Whether what this tool returns is text somebody else wrote.
     *
     * A page or a file is not an instruction, but a model this size does not reliably know
     * that: text arriving as a tool result carries the same weight as the system prompt, and
     * a small model is very good at continuing a pattern it has just been shown. A file
     * holding a literal tool call does not need to be understood to be repeated.
     */
    val returnsUntrustedText: Boolean get() = false

    /**
     * Whether running this sends what it is given to somebody else.
     *
     * The pair of these two is the whole rule: reading untrusted text is safe, sending
     * things off the device is safe, and doing the second after the first is the one
     * combination that can carry a private file to a stranger without anybody asking.
     */
    val leavesTheDevice: Boolean get() = false

    /**
     * Runs the call and returns what the model should be told.
     *
     * Failures come back as text rather than exceptions, because a model that is told
     * "that host did not respond" can try something else, while an exception ends the turn
     * and tells the user nothing they can act on.
     */
    suspend fun run(call: ToolCall): String

    /**
     * The call this tool would make to answer [question] on its own, or null if it cannot
     * be sensibly built without the model's help.
     *
     * Exists because a model that names a tool in prose has decided to use it and only
     * failed to say so in the right syntax. Each tool builds its own arguments: only the
     * tool knows what its schema means, and a search's argument is the question itself
     * while a fetch's is a URL that has to come from somewhere.
     */
    fun callFor(question: String): ToolCall? = null
}

/** What the model is allowed to reach for, and how it is found by name. */
class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.definition.name }

    val all: List<Tool> = tools

    /** The definitions handed to the engine, which renders them into the model's own syntax. */
    val definitions: List<ToolDefinition> = tools.map { it.definition }

    fun find(name: String): Tool? = byName[name]

    /** The subset the user has switched on, in the order they were registered. */
    fun enabled(names: Set<String>): ToolRegistry =
        ToolRegistry(all.filter { it.definition.name in names })
}

/**
 * How much rope the model gets this turn.
 *
 * Named after what the user asked for rather than after an internal state, because the
 * mode is chosen by typing `/plan` or `/auto` and has to mean the same thing in both
 * places.
 */
enum class AgentMode(val command: String, val label: String, val description: String) {
    /**
     * The model may call tools, and each call waits for the user.
     *
     * Not the default. Approval is per call, and a model that searches three times to
     * answer one question produces three prompts; the fourth one nobody reads. Worth
     * having for anyone who wants it, wrong to impose.
     */
    ASK("ask", "Ask first", "Approve each tool before it runs"),

    /**
     * The default. Tools run without asking, and the transcript says what ran.
     *
     * Defensible because of what the tools are: they read the public web and nothing
     * else. Nothing here writes a file, spends money, or touches anything private, so
     * the cost of a wrong call is a wasted second rather than damage.
     */
    AUTO("auto", "Auto", "Run tools without asking"),

    /**
     * No tools run at all. The model says what it would do.
     *
     * The point is a turn you can read before anything happens, which is what makes it
     * worth having on a phone where the alternative is watching results arrive.
     */
    PLAN("plan", "Plan", "Say what it would do, run nothing"),
    ;

    companion object {
        fun of(command: String): AgentMode? =
            entries.firstOrNull { it.command.equals(command, ignoreCase = true) }
    }
}

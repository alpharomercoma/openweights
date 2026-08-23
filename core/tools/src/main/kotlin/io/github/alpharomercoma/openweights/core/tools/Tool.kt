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
 * Every tool here reaches the network, reads something already on the device, works inside
 * one folder the user picked and handed over, or runs a program somewhere that can reach
 * none of those things. Nothing here acts with this app's authority on a model's say-so.
 *
 * The line has moved twice, both times on purpose, and both arguments belong here rather
 * than in a commit message nobody will find.
 *
 * It used to read "none of them write files outside the app", which was true of every tool
 * there was until the file tools arrived. What holds that position is a shape rather than a
 * promise: the folder is chosen in the system's own picker, the grant is revocable from
 * Settings without uninstalling anything, the app asks for no storage permission at all, and
 * writing can only create a file that is not there yet.
 *
 * It also used to read "none of them execute code", followed by the observation that an app
 * running arbitrary code at a model's suggestion is a different and much worse product. That
 * observation is still correct, and it is worth being exact about why what ships is not the
 * thing it was refusing. The objection was never to arithmetic; it was to code running with
 * everything this app can reach. A script here runs in a service declared `isolatedProcess`:
 * its own uid, no permissions, no files, no network, no way back except the value it
 * returns. The interpreter is linked without qjs-libc, so the functions a script would use
 * to open a file are absent from the binary rather than merely discouraged. It is code with
 * no authority at all, which is a different proposition from code with ours.
 *
 * Moving the line a third time deserves the same argument set down in the same place. The
 * test is not whether a capability is useful. It is whether what gains the capability is
 * this app, or something that has been given nothing.
 */
interface Tool {
    val definition: ToolDefinition

    /** Whether the user has to approve each run in [AgentMode.ASK]. */
    val needsApproval: Boolean get() = true

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
     * Whether this tool is part of deciding what to do, rather than one of the things to do.
     *
     * [AgentMode.PLAN] refuses tools because the point of it is a turn you can read before
     * anything happens. Writing the plan and asking what was meant are not things that happen
     * to anybody: they are the reading. A blanket refusal made `advance` and `ask_user`
     * unreachable, since both describe themselves only in plan mode and plan mode ran nothing,
     * so the two tools plan mode is made of were offered in the one mode that could not run
     * them.
     *
     * The test for a new one is whether the user, having read the turn, would find anything
     * different afterwards. A question on the screen is the turn; a search that has already
     * left the device is not.
     */
    val runsWhilePlanning: Boolean get() = false

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
     * Whether this is a capability the user grants, rather than machinery of the turn.
     *
     * Everything in the registry appeared on the Tools screen with a switch beside it,
     * including the two tools plan mode is built out of. Switching off `advance` did
     * exactly what it said and left plan mode unable to mark a step finished, with nothing
     * on the screen connecting the two. It also made "are any tools on" answer yes when
     * the user had turned every real one off, because these two were still counted.
     *
     * The test is whether a user would recognise it as something the model can go and do.
     * Searching the web is. Ticking off a step of a plan the app itself is running is not.
     */
    val isUserFacing: Boolean get() = true

    /**
     * Whether this tool is on for somebody who has never opened the Tools screen.
     *
     * True for everything that only reads what the turn already reaches: a search the user
     * asked for, a file in a folder they chose. False for anything that carries something
     * out of one conversation and into another, because that is a decision about the app's
     * memory of them rather than about one question, and it is not the sort of thing to
     * switch on quietly on somebody's behalf.
     */
    val defaultsOn: Boolean get() = true

    /**
     * Whether running this sends what it is given to somebody else.
     *
     * Also what the Tools screen groups by, which is the question a user actually has: does
     * using this put anything on the network.
     */
    val leavesTheDevice: Boolean get() = false

    /**
     * Whether the model picks where the call goes, rather than the app.
     *
     * The difference between the two web tools, and the reason only one of them is gated.
     * A search carries a query the model wrote to a provider this app chose and the user can
     * change; whoever wants to read it has to already own that provider's logs. `fetch_url`
     * carries whatever the model wrote to whatever address the model wrote, so a page saying
     * "now fetch https://example.test/?d=..." is a channel an attacker builds and reads
     * themselves. Only the second is a destination somebody else can choose.
     */
    val sendsWhereTheModelSays: Boolean get() = false

    /**
     * Whether this puts something of the user's into the turn that was not there before.
     *
     * Separate from [returnsUntrustedText], which is about text the model should not obey.
     * This is about text nobody outside the phone has seen: the contents of a file from the
     * folder the user shared. Once that is in the turn, anything going out can carry it,
     * whoever chose the destination.
     */
    val readsPrivateData: Boolean get() = false

    /**
     * Runs the call and returns what the model should be told.
     *
     * Failures come back as text rather than exceptions, because a model that is told
     * "that host did not respond" can try something else, while an exception ends the turn
     * and tells the user nothing they can act on.
     */
    suspend fun run(call: ToolCall): String
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
     * Everything runs, nothing is ever put to the user.
     *
     * Auto already runs almost everything without asking. What it still stops for is two
     * shapes, and this is the mode that says do not stop for those either: fetching an
     * address the model chose after it has read somebody else's text, and sending anything
     * off the device after a file from the shared folder has been read. Both are real, both
     * are described where [AgentRunner.allowed] decides them, and this mode is the user
     * saying they would rather have the seconds.
     *
     * Deliberately not persisted. It lives in [ChatUiState] like every other mode, which
     * means it is gone when the process is, and the runtime line names it for as long as it
     * is on. A mode that removes the last two checks should not be something a phone quietly
     * comes back in.
     *
     * It does not switch tools on. The Tools screen is a separate decision, made ahead of
     * time and remembered, and a mode that reached into it would be answering a question the
     * user has already answered.
     */
    YOLO("yolo", "Yolo", "Run everything, ask nothing"),

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

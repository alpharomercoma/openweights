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
import javax.inject.Inject

/**
 * Keeps one thing worth knowing next time.
 *
 * A tool rather than an extraction pass, which is the choice worth explaining. The
 * alternative is to read every finished conversation with the model and pull facts out of
 * it, which is what a server does because a server has spare capacity between requests. A
 * phone does not: that pass is a second full generation the user did not ask for, on a chip
 * they are holding, and it runs whether or not the conversation had anything in it worth
 * keeping.
 *
 * A tool moves the decision to the one moment the model already has the context loaded and
 * is already thinking about what matters, and it costs nothing on a conversation where
 * nothing was worth keeping, which is most of them.
 *
 * ### Off unless asked
 *
 * [defaultsOn] is false, and it is the only tool here that sets it. Everything else reads
 * what the turn already reaches. This carries something out of one conversation and into
 * every future one, which is a decision about the app's memory of a person rather than
 * about one question, and not the sort of thing to switch on quietly on their behalf.
 */
class RememberTool @Inject constructor(private val memory: Memory) : Tool {
    override val definition = ToolDefinition(
        name = "remember",
        description = "Keep one short fact about the user for future conversations: a " +
            "preference, a name, how they like answers written. Only for something that " +
            "stays true after this conversation ends. Not for what was just said, which " +
            "you can already see, and not for anything they would not expect to be kept.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "fact": {
                  "type": "string",
                  "description": "One sentence, in the third person, like 'Prefers answers without preamble'"
                }
              },
              "required": ["fact"]
            }
        """.trimIndent(),
    )

    /** See the note on the class: this is the one tool that starts switched off. */
    override val defaultsOn: Boolean = false

    /**
     * Asks first, and the old reasoning for not asking had the threat backwards.
     *
     * It said writing to memory reads nothing, so it cannot be the step that leaks
     * anything, and that much is true. Leaking was never the risk. This is the only tool
     * whose effect outlives the conversation: what it writes is replayed into the system
     * message of every future turn, ahead of the user's own words, in the part of the
     * prompt the cache keeps stable.
     *
     * So a page or a file that talks the model into calling this once has written itself
     * into the app's instructions permanently, and clearing the chat does not undo it. That
     * is a persistent prompt injection with a single unattended tool call as its whole cost,
     * and no other tool here can be made to do anything comparable.
     *
     * One tap, showing the exact sentence, is what the industry does for the same reason:
     * a memory the user did not agree to is not their memory. It is also cheap, because
     * this tool is off by default and fires rarely when on.
     *
     * Setting this alone was not enough and the note here used to imply it was.
     * [needsApproval] is an ASK-mode question by design, so in AUTO, which is the default,
     * the tap never happened and the injection this was written to stop went through
     * unseen. [alwaysAsks] below is the flag that actually asks.
     */
    override val needsApproval: Boolean = true

    /** Its effect is every future prompt, so it asks in AUTO too. See [Tool.alwaysAsks]. */
    override val alwaysAsks: Boolean = true

    override suspend fun run(call: ToolCall): String {
        val fact = call.argument("fact", "text", "note")
            ?: return "No fact was given. Call remember again with one short sentence."
        return memory.remember(fact)
    }
}

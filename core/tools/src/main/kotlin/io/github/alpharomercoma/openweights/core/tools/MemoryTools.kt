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
 * ### Half of a pair, and why the halves are separate
 *
 * Saving and reading used to be one switch: turning "remember" on both let the model write
 * facts and pushed every saved fact into the head of every future prompt. Those are two
 * different decisions about a person's data — what the app may keep, and what every
 * conversation gets told — and a switch that decides both at once cannot be set to "keep
 * notes but only bring them up when asked". They are also two different costs: injection
 * spent its tokens in the prompt of every turn of every conversation, relevant or not,
 * where [ReadMemoryTool] costs nothing until the model actually asks.
 *
 * ### Off unless asked
 *
 * [defaultsOn] is false for both halves, and they are the only tools that set it.
 * Everything else reads what the turn already reaches. This one carries something out of
 * one conversation and into every future one, which is a decision about the app's memory
 * of a person rather than about one question, and not the sort of thing to switch on
 * quietly on their behalf.
 */
class SaveMemoryTool @Inject constructor(private val memory: Memory) : Tool {
    override val definition = ToolDefinition(
        name = NAME,
        description = "Save one short fact about the user for future conversations: a " +
            "preference, a name, how they like answers written. Only for what stays true " +
            "after this conversation ends, never for what was just said.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "fact": {
                  "type": "string",
                  "description": "One sentence, third person, like 'Prefers answers without preamble'"
                }
              },
              "required": ["fact"]
            }
        """.trimIndent(),
    )

    /** See the note on the class: memory starts switched off, both halves. */
    override val defaultsOn: Boolean = false

    /**
     * Asks first, and the old reasoning for not asking had the threat backwards.
     *
     * Leaking was never the risk: writing to memory reads nothing. This is the only tool
     * whose effect outlives the conversation: what it writes can be read back into any
     * future turn, ahead of the user's own words. A page or a file that talks the model
     * into calling this once has written itself into the app's memory of its user
     * permanently, and clearing the chat does not undo it. That is a persistent prompt
     * injection with a single unattended tool call as its whole cost.
     *
     * One tap, showing the exact sentence, is what the industry does for the same reason:
     * a memory the user did not agree to is not their memory. It is also cheap, because
     * this tool is off by default and fires rarely when on.
     *
     * [needsApproval] alone is an ASK-mode question by design, so in AUTO, which is the
     * default, the tap never happened and the injection this was written to stop went
     * through unseen. [alwaysAsks] is the flag that actually asks.
     */
    override val needsApproval: Boolean = true

    /** Its effect is every future prompt, so it asks in AUTO too. See [Tool.alwaysAsks]. */
    override val alwaysAsks: Boolean = true

    override suspend fun run(call: ToolCall): String = execute(call).text

    override suspend fun execute(call: ToolCall): ToolExecution {
        val fact = call.argument("fact", "text", "note")
            ?: return ToolExecution.rejected(
                "No fact was given. Call $NAME again with one short sentence.",
            )
        return memory.remember(fact)
    }

    companion object {
        const val NAME = "save_memory"

        /** What this tool was called when saving and reading were one switch. */
        const val LEGACY_NAME = "remember"
    }
}

/**
 * Reads back what [SaveMemoryTool] kept.
 *
 * A tool rather than a block injected into every prompt, which is what this replaced. The
 * injected block cost its tokens on the first turn of every conversation whether or not the
 * conversation was about its user, and it re-entered the prompt ahead of the user's own
 * words, which is the position a prompt injection would choose. Read on demand, the facts
 * cost nothing until a question actually depends on who is asking, and they arrive as a
 * tool result — text the model has learned to treat as information rather than orders.
 *
 * The price of the trade is honesty about who pays it: a model has to think of calling
 * this, where the injected block was simply there. The description says when, which is the
 * only lever a prompt has; whether a small model pulls it is measured in the benchmark, not
 * assumed here.
 */
class ReadMemoryTool @Inject constructor(private val memory: Memory) : Tool {
    override val definition = ToolDefinition(
        name = NAME,
        description = "Read the short facts saved about this user in earlier " +
            "conversations. Call it before answering anything that depends on who the " +
            "user is or what they prefer.",
        parametersJson = """
            {
              "type": "object",
              "properties": {}
            }
        """.trimIndent(),
    )

    /** Off with its other half; see [SaveMemoryTool]. */
    override val defaultsOn: Boolean = false

    /** The facts are about a person; anything they touch must not leave quietly. */
    override val readsPrivateData: Boolean = true

    override suspend fun run(call: ToolCall): String = execute(call).text

    override suspend fun execute(call: ToolCall): ToolExecution {
        val facts = memory.asPrompt()
            ?: return ToolExecution("Nothing is saved about this user yet.")
        return ToolExecution(facts)
    }

    companion object {
        const val NAME = "read_memory"
    }
}

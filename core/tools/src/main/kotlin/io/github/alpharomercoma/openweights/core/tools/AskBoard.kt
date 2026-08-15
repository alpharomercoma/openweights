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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Something the model wants to know before it commits to an approach.
 *
 * @param text the question itself.
 * @param options what it suggests, which may be empty: typing an answer is always allowed and
 *   is the only path that works when a model cannot manage a list.
 * @param multiple whether more than one option may be chosen.
 */
data class UserQuestion(
    val text: String,
    val options: List<String> = emptyList(),
    val multiple: Boolean = false,
)

/**
 * The question the model is waiting on, and the answer when it arrives.
 *
 * The same shape as the approval prompt, which already proves a suspending tool can wait on
 * a person: a deferred on one side, a card above the composer on the other. Approval is the
 * special case of this with two fixed options and no room to type.
 */
@Singleton
class AskBoard @Inject constructor() {
    private val current = MutableStateFlow<UserQuestion?>(null)
    private var waiting: CompletableDeferred<String>? = null

    val pending: StateFlow<UserQuestion?> = current.asStateFlow()

    /**
     * Whether the tool describes itself to the model at all.
     *
     * Off outside plan mode, and that is not caution about the feature. Every tool in the
     * catalogue costs tokens to describe and makes the choice between the others measurably
     * harder, so a tool that is only useful while deciding what to do should not be on the
     * table while the model is deciding whether to search the web.
     */
    var offered: Boolean = false

    /** Suspends until the person answers, which is the whole point of the tool. */
    suspend fun ask(question: UserQuestion): String {
        val answer = CompletableDeferred<String>()
        waiting = answer
        current.value = question
        return try {
            answer.await()
        } finally {
            waiting = null
            current.value = null
        }
    }

    /** What the person chose or typed, which goes back to the model as the tool's result. */
    fun answer(reply: String) {
        waiting?.complete(reply)
    }
}

/**
 * Asks the user a question and waits for the answer.
 *
 * The thing a plan is missing without it. A model that cannot ask has to guess which of two
 * readings of a request was meant, and at this size it guesses wrong often enough that the
 * plan is then wrong from its first step. One round trip up front is cheaper than four
 * afterwards.
 *
 * Options are a suggestion rather than a contract. A model that emits none, or emits them
 * malformed, still produces a question with a text box under it, because a tool whose value
 * depends on a 1B model getting a JSON array right would have no value at all.
 */
@Singleton
class AskUserTool @Inject constructor(private val board: AskBoard) : Tool {
    override val definition = ToolDefinition(
        name = "ask_user",
        description = "Ask the user a question when the request could mean more than one " +
            "thing, and wait for the answer. Suggest up to four options if you have them.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "question": {
                  "type": "string",
                  "description": "The question to put to the user, in one sentence"
                },
                "options": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "Up to four short answers to offer, or none"
                },
                "multiple": {
                  "type": "boolean",
                  "description": "True when more than one option may be chosen"
                }
              },
              "required": ["question"]
            }
        """.trimIndent(),
    )

    override val isAvailable: Boolean get() = board.offered

    /** The question is the approval: there is nothing to confirm before showing it. */
    override val needsApproval: Boolean = false

    /**
     * The one tool whose whole purpose is the pass before anything happens.
     *
     * Without this it was offered only in plan mode and refused only in plan mode, which is
     * every time it was offered.
     */
    override val runsWhilePlanning: Boolean = true

    override val chains: Boolean = true

    /** What comes back is what the person typed or chose, which is theirs and not a stranger's. */
    override val returnsUntrustedText: Boolean = false

    override suspend fun run(call: ToolCall): String {
        val question = call.argument("question", "text", "prompt")
            ?: return "No question was given. Call ask_user again with a question."
        val answer = board.ask(
            UserQuestion(
                text = question,
                options = call.optionList(),
                multiple = call.argument("multiple", "multi")?.toBoolean() ?: false,
            ),
        )
        return answer.ifBlank { "The user did not answer. Choose for them and say what you chose." }
    }
}

/**
 * The options the model offered, or none.
 *
 * Never a failure. A malformed array means the question is asked with a text box and nothing
 * else, which is the same question with one fewer convenience, and is a great deal better
 * than refusing to ask it.
 */
private fun ToolCall.optionList(): List<String> {
    val parsed = runCatching {
        Json.parseToJsonElement(argumentsJson).jsonObject["options"]?.jsonArray
    }.getOrNull() ?: return emptyList()
    return parsed.mapNotNull { entry ->
        runCatching { entry.jsonPrimitive.content }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }.take(MAX_OPTIONS)
}

/**
 * Four, which is a row of chips on a phone and a choice a person can read at a glance.
 *
 * More than that is a menu, and a menu is what this exists to avoid: the model asking a
 * question it could have answered by picking one itself.
 */
private const val MAX_OPTIONS = 4

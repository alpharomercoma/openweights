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
 * The shape a model is asked to write a call in, when the prompt has to say.
 *
 * Two, because which one a small model handles better is a measurement rather than an
 * opinion, and the benchmark runs them as arms against the same cases.
 */
enum class CallFormat(val instruction: String) {
    /**
     * One flat JSON object, which is the least there is to get wrong.
     *
     * A 1B model copies a shape it has just been shown far more reliably than it invents one,
     * and every extra brace and tag is another thing to drop.
     */
    BARE(
        "To use one, reply with only this and nothing else:\n" +
            """{"tool": "name", "arguments": {"argument": "value"}}""" + "\n" +
            "Do not explain that you are going to use it. Just send the object. " +
            "If no tool is needed, answer normally and send no object.",
    ),

    /**
     * The same object inside the tags Hermes uses.
     *
     * More characters than [BARE] and, on the argument above, more to get wrong. What makes
     * it worth measuring anyway is that it is not a shape the model has to be taught: these
     * tags are what a large share of tool fine-tuning data looks like, so for the models that
     * read their format out of the prompt it may already be in the weights.
     */
    TAGGED(
        "To use one, reply with only this and nothing else:\n" +
            """<tool_call>{"name": "tool_name", "arguments": {"argument": "value"}}</tool_call>""" +
            "\n" +
            "Do not explain that you are going to use it. Just send the tags. " +
            "If no tool is needed, answer normally and send no tags.",
    ),
}

/**
 * Tools for a model whose template will not carry them.
 *
 * Most of the good small models cannot do this the proper way. A chat template either knows
 * about tools or silently drops them, and Gemma 3 1B and LFM2 1.2B both drop them: the
 * definitions never reach the model, it answers in prose, and the app quietly stops being an
 * agent without anything looking broken. Measured over the three families this app is tested
 * against, two of them were in that state.
 *
 * So the definitions go where every template does carry text, which is the conversation
 * itself, and the reply is read back for a call. It is worse than a template that was built
 * for this: it costs prompt tokens the native path gets for free, and a model can write
 * something that looks like a call inside an ordinary sentence. It is better than the
 * alternative, which is nothing at all.
 *
 * Which shape to ask for is [CallFormat], and it is a live question rather than a settled
 * one, so it is a parameter with an arm in the benchmark behind it.
 */
object ToolPrompting {
    /** What to add to the system message so a model without tool support can still call. */
    fun describe(tools: List<ToolDefinition>, format: CallFormat = CallFormat.BARE): String {
        if (tools.isEmpty()) return ""
        val listed = tools.joinToString("\n") { tool ->
            "- ${tool.name}: ${tool.description} Arguments: ${tool.parametersJson.compact()}"
        }
        return "You can use these tools:\n$listed\n\n" + format.instruction
    }

    /**
     * The call in a reply, if the model wrote one.
     *
     * Deliberately strict about the shape and forgiving about what surrounds it. A model told
     * to send only the object usually sends a sentence as well, so the object is looked for
     * anywhere; but it has to name something real in a key, or a sentence that merely mentions
     * a tool becomes an action.
     *
     * That strictness is not an argument against the prose salvage path in `TurnRunner`, which
     * does turn a named tool into a call and is the right thing there. The two are ordered by
     * trust rather than in conflict: this is a parser, and prose is not the syntax it parses,
     * so it says no and lets the next path decide. Salvage then decides under three conditions
     * this one has no business knowing about, being that the tool was offered in the first
     * place, that it is not one whose reach the model chooses, and that exactly one tool could
     * have built a call from the question. A parser that guessed would take that decision away
     * from the layer that can make it properly.
     */
    fun parse(reply: String, tools: ToolRegistry): ToolCall? {
        val marker = reply.callKey()
        if (marker < 0) return null

        // Read out of the key rather than found anywhere in the reply. Scanning the whole
        // text for any registered name meant a name written inside an argument won, in
        // registry order and regardless of where the call was: a file whose contents mention
        // web_search became a call to web_search, carrying read_file's path as its query.
        val named = reply.namedAt(marker)
        val name = tools.all.map { it.definition.name }.firstOrNull { it.matches(named) }
            ?: return null

        // An arguments object that starts and never closes is a call that was cut off, not a
        // call with no arguments. Dispatching it with an empty object spends a round on a
        // tool that can only answer "you gave me nothing", and the model is then told its
        // own truncation was a missing argument. A tool that genuinely takes none says so by
        // having no arguments key at all, which is the other branch.
        val started = reply.keyAt(ARGUMENT_KEYS, marker) >= 0
        val arguments = reply.argumentsAfter(marker)
        if (started && arguments == null) return null

        return ToolCall(id = name, name = name, argumentsJson = (arguments ?: "{}").asJson())
    }

    /**
     * Where the key naming the tool begins, in whichever spelling the model used.
     *
     * Two are accepted rather than one, and the second is not a guess: `tool` is what
     * [CallFormat.BARE] asks for, and `name` is what the Hermes envelope uses and with it a
     * large share of the instruction data these models were tuned on. A model that has seen
     * ten thousand examples of one spelling will write that one whatever the prompt says, so
     * refusing it means refusing the call the model actually made.
     *
     * Nested wrappers come out of this too. `{"type":"function","function":{"name":...}}` has
     * its name in the same key, and the arguments are found from there by the same walk.
     */
    private fun String.callKey(): Int = keyAt(CALL_KEYS, from = 0)

    /** The earliest of these keys at or after [from], or -1 when none of them is there. */
    private fun String.keyAt(keys: List<String>, from: Int): Int =
        keys.mapNotNull { key -> indexOf(key, startIndex = from).takeIf { it >= 0 } }
            .minOrNull() ?: -1

    /**
     * Both spellings of both keys, because the quote is not part of the decision.
     *
     * Measured on a phone: Hammer 2.1, asked to read a URL, wrote
     * `[{'type': 'function', 'function': {'name': 'fetch_url', 'arguments': {...}}}]`. Its
     * template asks for JSON and it produced a Python dict repr, which is what a model tuned
     * on Python examples does. Every key was right and every name was right; only the quote
     * was, and dropping the call over that is refusing a decision the model made correctly.
     */
    private val CALL_KEYS = listOf("\"tool\"", "'tool'", "\"name\"", "'name'")

    private val ARGUMENT_KEYS = listOf("\"arguments\"", "'arguments'")

    /** Either quote, since a model that opened with one closes with the same one. */
    private const val QUOTES = "\"'"

    /**
     * The quoted string the call key names, which is the tool the model asked for.
     *
     * Taken by hand rather than by parsing, for the reason [argumentsAfter] gives: what a
     * model this size writes is rarely valid JSON all the way through, and this only has to
     * find one value.
     */
    private fun String.namedAt(marker: Int): String? {
        val colon = indexOf(':', startIndex = marker).takeIf { it >= 0 } ?: return null
        val open = indexOfAny(QUOTES.toCharArray(), startIndex = colon + 1)
            .takeIf { it >= 0 } ?: return null
        // Closed by the quote it opened with, not by whichever comes first. A name in double
        // quotes may contain an apostrophe, and ending on that returns a fragment that
        // matches no tool, which reads as the model naming something that does not exist.
        val close = indexOf(this[open], startIndex = open + 1).takeIf { it >= 0 } ?: return null
        return substring(open + 1, close)
    }

    /**
     * Whether what the model named is this tool.
     *
     * The last segment, because some tuning data namespaces a call as `functions.web_search`
     * and refusing that would refuse a call the model plainly made. Everything before the
     * last dot is a namespace; what follows it has to be the name exactly, which is the whole
     * difference from the substring match this replaced.
     */
    private fun String.matches(named: String?): Boolean =
        named?.trim()?.substringAfterLast('.').equals(this, ignoreCase = true)

    /**
     * The `arguments` object, taken by matching braces rather than by parsing.
     *
     * Whatever the model wrote is unlikely to be valid JSON all the way through, and the
     * tools already accept a mangled envelope, so this only has to find the right span. Depth
     * counting handles a nested object; a run-on that never closes returns nothing rather
     * than the rest of the reply.
     */
    private fun String.argumentsAfter(marker: Int): String? {
        val key = keyAt(ARGUMENT_KEYS, from = marker)
        if (key < 0) return null
        val open = indexOf('{', startIndex = key)
        if (open < 0) return null

        var depth = 0
        for (index in open until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return substring(open, index + 1)
                }
            }
        }
        return null
    }

    /**
     * The same object with its strings quoted the way JSON wants them.
     *
     * Reading the name is only half of a call. Every tool reads its own arguments by parsing
     * this string as JSON, so `{'url': 'https://example.com'}` is a call that names the right
     * tool and then arrives with nothing in it, and the tool answers that no url was given
     * while the url sits there in the text. Hammer 2.1 writes exactly that, on a phone, for
     * every call it makes.
     *
     * Walked rather than replaced, because the two quotes are not interchangeable: an
     * apostrophe inside a double-quoted value is a letter, and turning it into a delimiter
     * breaks every ordinary English possessive. Only a single quote that is acting as a
     * delimiter becomes a double one, and a double quote inside such a span is escaped on the
     * way out so the result still parses.
     */
    private fun String.asJson(): String {
        if (!contains('\'')) return this

        val out = StringBuilder(length)
        var quote: Char? = null
        var index = 0
        while (index < length) {
            val char = this[index]
            when {
                // Inside a string an escape covers whatever follows it, quotes included.
                quote != null && char == '\\' && index + 1 < length -> {
                    out.append(char).append(this[index + 1])
                    index++
                }
                quote == null && (char == '\'' || char == '"') -> {
                    quote = char
                    out.append('"')
                }
                char == quote -> {
                    quote = null
                    out.append('"')
                }
                char == '"' && quote == '\'' -> out.append("\\\"")
                else -> out.append(char)
            }
            index++
        }
        return out.toString()
    }

    /** The schema on one line, since a pretty-printed one is only longer. */
    private fun String.compact(): String = replace(Regex("\\s+"), " ").trim()
}

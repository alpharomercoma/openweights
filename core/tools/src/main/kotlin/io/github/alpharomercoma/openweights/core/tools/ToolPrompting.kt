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
 * The format asked for is one flat JSON object rather than anything nested or tagged. A 1B
 * model copies a shape it has just been shown far more reliably than it invents one, and
 * every extra brace is another thing to get wrong.
 */
object ToolPrompting {
    /** What to add to the system message so a model without tool support can still call. */
    fun describe(tools: List<ToolDefinition>): String {
        if (tools.isEmpty()) return ""
        val listed = tools.joinToString("\n") { tool ->
            "- ${tool.name}: ${tool.description} Arguments: ${tool.parametersJson.compact()}"
        }
        return "You can use these tools:\n$listed\n\n" +
            "To use one, reply with only this and nothing else:\n" +
            """{"tool": "name", "arguments": {"argument": "value"}}""" + "\n" +
            "Do not explain that you are going to use it. Just send the object. " +
            "If no tool is needed, answer normally and send no object."
    }

    /**
     * The call in a reply, if the model wrote one.
     *
     * Deliberately strict about the shape and forgiving about what surrounds it. A model told
     * to send only the object usually sends a sentence as well, so the object is looked for
     * anywhere; but it has to have a `tool` naming something real, or a sentence that merely
     * mentions a tool becomes an action, which is the mistake the prose salvage path already
     * makes and one worth not making twice.
     */
    fun parse(reply: String, tools: ToolRegistry): ToolCall? {
        val name = tools.all.map { it.definition.name }
            .firstOrNull { reply.contains("\"$it\"") }
            ?: return null
        val marker = reply.indexOf("\"tool\"")
        if (marker < 0) return null

        // An arguments object that starts and never closes is a call that was cut off, not a
        // call with no arguments. Dispatching it with an empty object spends a round on a
        // tool that can only answer "you gave me nothing", and the model is then told its
        // own truncation was a missing argument. A tool that genuinely takes none says so by
        // having no arguments key at all, which is the other branch.
        val started = reply.indexOf("\"arguments\"", startIndex = marker) >= 0
        val arguments = reply.argumentsAfter(marker)
        if (started && arguments == null) return null

        return ToolCall(id = name, name = name, argumentsJson = arguments ?: "{}")
    }

    /**
     * The `arguments` object, taken by matching braces rather than by parsing.
     *
     * Whatever the model wrote is unlikely to be valid JSON all the way through, and the
     * tools already accept a mangled envelope, so this only has to find the right span. Depth
     * counting handles a nested object; a run-on that never closes returns nothing rather
     * than the rest of the reply.
     */
    private fun String.argumentsAfter(marker: Int): String? {
        val key = indexOf("\"arguments\"", startIndex = marker)
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

    /** The schema on one line, since a pretty-printed one is only longer. */
    private fun String.compact(): String = replace(Regex("\\s+"), " ").trim()
}

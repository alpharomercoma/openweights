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

package io.github.alpharomercoma.openweights.core.common.model

/**
 * The JSON spellings the prompt templates share.
 *
 * Each template splices [ToolDefinition.parametersJson] in verbatim rather than
 * re-encoding it — re-encoding would reorder keys, and the schema is the model's only
 * description of what the arguments mean. What varies per family is the wrapping, and
 * that stays in each family's own file.
 */

/** A tool as the OpenAI-shaped object most templates were trained to read. */
internal fun ToolDefinition.asToolJson(): String =
    """{"type": "function", "function": {"name": ${name.jsonQuoted()}, """ +
        """"description": ${description.jsonQuoted()}, "parameters": $parametersJson}}"""

/** [this] as a JSON string literal, escaped the way `json.dumps` writes one. */
internal fun String.jsonQuoted(): String = buildString {
    append('"')
    this@jsonQuoted.forEach { character ->
        when {
            character == '"' -> append("\\\"")
            character == '\\' -> append("\\\\")
            character == '\n' -> append("\\n")
            character == '\r' -> append("\\r")
            character == '\t' -> append("\\t")
            character < ' ' -> append("\\u")
                .append(character.code.toString(HEX_RADIX).padStart(ESCAPE_DIGITS, '0'))
            else -> append(character)
        }
    }
    append('"')
}

/**
 * Compact JSON re-laid-out the way `json.dumps(indent=4)` writes it, keys untouched.
 *
 * Llama 3.2 renders each tool schema indented four deep, and the schema arrives here as
 * the compact text [ToolDefinition.parametersJson] holds. This walks the text rather than
 * parsing it into a tree, because a tree would re-order nothing but could still re-escape
 * strings; character-by-character, the only thing that changes is whitespace.
 */
internal fun reindentJson(json: String, indent: Int = LLAMA_INDENT): String {
    val out = StringBuilder()
    val walker = JsonWalker(json, indent, out)
    while (walker.index < json.length) walker.step()
    return out.toString()
}

/** The cursor for [reindentJson]: one character per [step], with string state carried. */
private class JsonWalker(
    private val json: String,
    private val indent: Int,
    private val out: StringBuilder,
) {
    var index = 0
    private var depth = 0
    private var inString = false
    private var escaped = false

    fun step() {
        val character = json[index]
        when {
            inString -> stringCharacter(character)
            character == '"' -> {
                out.append(character)
                inString = true
                escaped = false
            }

            character == '{' || character == '[' -> open(character)
            character == '}' || character == ']' -> {
                depth -= 1
                out.append('\n').append(" ".repeat(depth * indent)).append(character)
            }

            character == ',' -> out.append(",\n").append(" ".repeat(depth * indent))
            character == ':' -> out.append(": ")
            character == ' ' -> Unit // The compact form's own spacing is re-created above.
            else -> out.append(character)
        }
        index += 1
    }

    private fun stringCharacter(character: Char) {
        out.append(character)
        escaped = !escaped && character == '\\'
        if (character == '"' && !escaped) inString = false
        if (character != '\\') escaped = false
    }

    private fun open(character: Char) {
        // An empty container stays on one line, which is json.dumps's own rule.
        val closer = if (character == '{') '}' else ']'
        val next = json.nextMeaningful(index + 1)
        if (next != null && json[next] == closer) {
            out.append(character).append(closer)
            index = next
        } else {
            depth += 1
            out.append(character).append('\n').append(" ".repeat(depth * indent))
        }
    }
}

/** The next index in [this] holding something other than a space, or null. */
private fun String.nextMeaningful(from: Int): Int? {
    var index = from
    while (index < length && this[index] == ' ') index += 1
    return index.takeIf { it < length }
}

private const val HEX_RADIX = 16
private const val ESCAPE_DIGITS = 4
private const val LLAMA_INDENT = 4

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

/** A reply with any tool calls lifted out of the prose. */
data class ParsedToolCalls(val text: String, val calls: List<ToolCall>)

/**
 * Extracts tool calls that llama.cpp's own parser did not recognise.
 *
 * llama.cpp knows the Hermes, Llama 3.x, Functionary and Mistral formats, and is tried
 * first. It does not know every model: LFM2.5 emits Python-style calls wrapped in
 * `<|tool_call_start|>` markers, which parse as ordinary prose and would otherwise be
 * shown to the user as if the model had answered.
 *
 * Kept small and format-specific rather than clever. A parser that guesses
 * at unknown syntax produces confident nonsense; one that recognises named formats and
 * gives up otherwise is safe to fall back on.
 */
object ToolCallParser {

    fun parse(raw: String): ParsedToolCalls {
        parseLfmStyle(raw)?.let { return it }
        parseTaggedJson(raw)?.let { return it }
        return ParsedToolCalls(raw, emptyList())
    }

    /** `<|tool_call_start|>[name(arg='value', other=2)]<|tool_call_end|>`: LFM2. */
    private fun parseLfmStyle(raw: String): ParsedToolCalls? {
        val start = raw.indexOf(LFM_START)
        if (start < 0) return null
        val end = raw.indexOf(LFM_END, start)
        if (end < 0) return null

        val body = raw.substring(start + LFM_START.length, end).trim().removeSurrounding("[", "]")
        val calls = body.splitTopLevel(',')
            .mapNotNull { it.trim().toPythonStyleCall() }
        if (calls.isEmpty()) return null

        val text = (raw.take(start) + raw.substring(end + LFM_END.length)).trim()
        return ParsedToolCalls(text, calls)
    }

    /** `<tool_call>{"name": "...", "arguments": {...}}</tool_call>`: Hermes and friends. */
    private fun parseTaggedJson(raw: String): ParsedToolCalls? {
        val start = raw.indexOf(JSON_START)
        if (start < 0) return null
        val end = raw.indexOf(JSON_END, start)
        if (end < 0) return null

        val body = raw.substring(start + JSON_START.length, end).trim()
        val name = body.jsonStringField("name") ?: return null
        val arguments = body.jsonObjectField("arguments") ?: "{}"

        val text = (raw.take(start) + raw.substring(end + JSON_END.length)).trim()
        return ParsedToolCalls(
            text,
            listOf(ToolCall(id = name, name = name, argumentsJson = arguments)),
        )
    }

    /** `name(arg='value', count=2)` to a call with JSON arguments. */
    private fun String.toPythonStyleCall(): ToolCall? {
        val open = indexOf('(')
        if (open <= 0 || !endsWith(")")) return null

        val name = take(open).trim()
        if (name.isEmpty()) return null

        val arguments = substring(open + 1, length - 1)
            .splitTopLevel(',')
            .mapNotNull { argument ->
                val equals = argument.indexOf('=')
                if (equals <= 0) return@mapNotNull null
                val key = argument.take(equals).trim()
                val value = argument.substring(equals + 1).trim()
                "\"$key\": ${value.toJsonValue()}"
            }

        return ToolCall(id = name, name = name, argumentsJson = "{${arguments.joinToString(", ")}}")
    }

    /** Python literals to JSON: quoted strings stay strings, bare numbers and booleans do not. */
    private fun String.toJsonValue(): String = when {
        startsWith("'") && endsWith("'") && length >= 2 -> drop(1).dropLast(1).asJsonString()
        startsWith("\"") -> this
        this == "True" -> "true"
        this == "False" -> "false"
        this == "None" -> "null"
        toDoubleOrNull() != null -> this
        startsWith("[") || startsWith("{") -> this
        else -> asJsonString()
    }

    /**
     * Quotes a value as JSON.
     *
     * Escaping only quotes would produce invalid JSON the moment a model passes a Windows
     * path or a newline: `path='C:\tmp'` is perfectly reasonable output.
     */
    private fun String.asJsonString(): String = buildString {
        append('"')
        this@asJsonString.forEach { character ->
            when {
                character == '\\' -> append("\\\\")
                character == '"' -> append("\\\"")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character < ' ' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
        append('"')
    }

    /** Splits on a separator, ignoring ones inside quotes, brackets, or braces. */
    private fun String.splitTopLevel(separator: Char): List<String> {
        val scanner = TopLevelSplitter(separator)
        forEach(scanner::accept)
        return scanner.finish()
    }

    /**
     * Splits argument lists without breaking on separators that are inside a string or a
     * nested structure: `note(text='Manila, Philippines')` is one argument, not two.
     */
    private class TopLevelSplitter(private val separator: Char) {
        private val parts = mutableListOf<String>()
        private val current = StringBuilder()
        private var depth = 0
        private var quote: Char? = null

        fun accept(character: Char) {
            val active = quote
            when {
                active != null -> {
                    current.append(character)
                    if (character == active) quote = null
                }

                character in QUOTES -> {
                    quote = character
                    current.append(character)
                }

                character in OPENERS -> {
                    depth++
                    current.append(character)
                }

                character in CLOSERS -> {
                    depth--
                    current.append(character)
                }

                character == separator && depth == 0 -> {
                    parts += current.toString()
                    current.clear()
                }

                else -> current.append(character)
            }
        }

        fun finish(): List<String> {
            if (current.isNotEmpty()) parts += current.toString()
            return parts
        }

        private companion object {
            const val QUOTES = "'\""
            const val OPENERS = "([{"
            const val CLOSERS = ")]}"
        }
    }

    private fun String.jsonStringField(field: String): String? {
        val at = indexOf("\"$field\"")
        if (at < 0) return null

        val colon = indexOf(':', at + field.length)
        val open = if (colon < 0) -1 else indexOf('"', colon)
        val close = if (open < 0) -1 else indexOf('"', open + 1)
        return if (close < 0) null else substring(open + 1, close)
    }

    private fun String.jsonObjectField(field: String): String? {
        val key = "\"$field\""
        val at = indexOf(key)
        if (at < 0) return null
        val open = indexOf('{', at + key.length)
        if (open < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in open until length) {
            val character = this[index]
            when {
                escaped -> escaped = false
                character == '\\' && inString -> escaped = true
                character == '"' -> inString = !inString
                inString -> Unit
                character == '{' -> depth++
                character == '}' -> {
                    depth--
                    // Braces inside a string value must not close the object early.
                    if (depth == 0) return substring(open, index + 1)
                }
            }
        }
        return null
    }

    private const val LFM_START = "<|tool_call_start|>"
    private const val LFM_END = "<|tool_call_end|>"
    private const val JSON_START = "<tool_call>"
    private const val JSON_END = "</tool_call>"
}

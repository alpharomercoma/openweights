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

/**
 * Reads an argument that is a lump of text rather than a word.
 *
 * [argument] parses the call as JSON and gives up if it will not parse, which is right for a
 * query or a url: those are short, and a model that mangled the envelope around one probably
 * mangled the value too. It is the wrong answer for the body of a file. Asking a small model
 * to put a page of text inside a JSON string means asking it to escape every quote, every
 * backslash and every newline in that text, and a 1.5B model does not reliably do it. The
 * failure is silent and total: the envelope does not parse, the argument reads as absent,
 * and the tool answers that no content was given while the content sits there in the call.
 *
 * So the strict reading is tried first and kept when it works, and only a call that has
 * already failed to parse gets scavenged. The value is taken from the first quote after the
 * key to the last quote before the closing brace, which is the shape a model produces when
 * it puts the long argument last, and that is where a long argument goes.
 *
 * This is recovery, not a second parser. It cannot tell an unescaped quote inside the text
 * from the one that ends it, and it does not try: taking the outermost pair is the reading
 * that keeps the most of what was meant.
 */
internal fun ToolCall.textArgument(vararg names: String): String? =
    argument(*names) ?: names.firstNotNullOfOrNull { argumentsJson.scavenge(it) }

/** Pulls one named value out of an envelope that is no longer valid JSON. */

/** A whole number argument, however the model chose to write it. */
internal fun ToolCall.intArgument(vararg names: String): Int? =
    textArgument(*names)?.trim()?.let { written ->
        // Takes the digits out rather than requiring a bare number, because a model writing
        // an interval writes "5", 5, and "5 minutes" in roughly equal measure, and refusing
        // the third would be refusing a call that said exactly what it meant.
        written.toIntOrNull() ?: DIGITS.find(written)?.value?.toIntOrNull()
    }

private val DIGITS = Regex("\\d+")

/**
 * A boolean argument, true only when the model actually said so.
 *
 * Absent, malformed, and anything that is not a plain `true` all read as false, because
 * every flag reaching this helper guards something a caller should have to ask for rather
 * than fall into. A JSON boolean is unquoted, so [scavenge] cannot see it: that one looks
 * for a quoted value and would find nothing here.
 */
internal fun ToolCall.flag(vararg names: String): Boolean =
    names.any { argumentsJson.booleanNamed(it) == true }

private fun String.booleanNamed(name: String): Boolean? {
    val key = indexOf("\"$name\"")
    if (key < 0) return null

    val colon = indexOf(':', startIndex = key + name.length + QUOTES)
    if (colon < 0) return null

    // Quoted or bare, because a model writing Pythonic calls and a harness rendering JSON
    // do not always agree on whether a boolean wears quotes.
    val rest = substring(colon + 1).trimStart().removePrefix("\"")
    return when {
        rest.startsWith("true", ignoreCase = true) -> true
        rest.startsWith("false", ignoreCase = true) -> false
        else -> null
    }
}

private fun String.scavenge(name: String): String? {
    val key = indexOf("\"$name\"")
    if (key < 0) return null

    val colon = indexOf(':', startIndex = key + name.length + QUOTES)
    if (colon < 0) return null

    val open = indexOf('"', startIndex = colon + 1)
    if (open < 0) return null

    // Everything up to the brace that closes the call, so the last quote found is the one
    // that closes the value rather than one belonging to the text inside it.
    val body = trimEnd().removeSuffix("}").trimEnd()
    val close = body.lastIndexOf('"')
    return if (close > open) body.substring(open + 1, close).unescaped() else null
}

/**
 * Turns the escapes a model did get right back into the characters they stand for.
 *
 * Walked rather than done with replace calls in sequence, because replacing `\\n` and then
 * `\\\\` turns a literal backslash followed by an n into a newline, which is the bug that
 * makes a Windows path or a regular expression come out wrong.
 */
private fun String.unescaped(): String {
    if (!contains('\\')) return this

    val out = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        val next = getOrNull(index + 1)
        if (char == '\\' && next != null) {
            out.append(next.unescape())
            index += 2
        } else {
            out.append(char)
            index += 1
        }
    }
    return out.toString()
}

private fun Char.unescape(): Char = when (this) {
    'n' -> '\n'
    't' -> '\t'
    'r' -> '\r'
    else -> this
}

/** The two quote characters around a key, which the colon comes after. */
private const val QUOTES = 2

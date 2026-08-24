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

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import org.junit.Test

/**
 * Getting a page of text out of a call a small model wrote.
 *
 * The tool that writes a file asks the model to put the whole file inside a JSON string,
 * which means escaping every quote, backslash and newline in it. A 1.5B model does not
 * reliably do that, and the failure is silent: the envelope will not parse, the argument
 * reads as missing, and the tool says no content was given while the content is sitting in
 * the call. Each of these is a way that has happened.
 */
class ToolArgumentsTest {
    private fun call(json: String) = ToolCall(id = "1", name = "write_file", argumentsJson = json)

    @Test
    fun `a properly escaped call is read the ordinary way`() {
        val json = """{"path":"notes.md","content":"Line one\nLine two"}"""

        assertThat(call(json).textArgument("content")).isEqualTo("Line one\nLine two")
    }

    @Test
    fun `raw newlines inside the value do not lose the value`() {
        // Valid JSON forbids a literal newline inside a string, so this does not parse at
        // all. It is also exactly what a model writing a file produces most of the time.
        val json = "{\"path\":\"notes.md\",\"content\":\"Line one\nLine two\"}"

        assertThat(call(json).textArgument("content")).isEqualTo("Line one\nLine two")
    }

    @Test
    fun `unescaped quotes inside the text are kept`() {
        val json = """{"path":"q.md","content":"She said "hello" and left"}"""

        assertThat(call(json).textArgument("content")).isEqualTo("""She said "hello" and left""")
    }

    @Test
    fun `a backslash before an n stays a backslash and an n`() {
        // The bug that comes from unescaping with a sequence of replace calls: turn \\n into
        // a newline first and a Windows path or a regular expression comes out mangled.
        val json = "{\"content\":\"C:\\\\Users\\\\note\nsecond line\"}"

        assertThat(call(json).textArgument("content")).contains("""C:\Users\note""")
    }

    @Test
    fun `code with braces and quotes survives`() {
        val json = "{\"path\":\"a.kt\",\"content\":\"fun main() { println(\"hi\") }\"}"

        assertThat(call(json).textArgument("content")).isEqualTo("""fun main() { println("hi") }""")
    }

    @Test
    fun `a missing key is still missing`() {
        assertThat(call("""{"path":"a.md"}""").textArgument("content")).isNull()
    }

    @Test
    fun `nothing is invented from an empty envelope`() {
        assertThat(call("{}").textArgument("content")).isNull()
        assertThat(call("not json at all").textArgument("content")).isNull()
    }

    @Test
    fun `the short arguments are still read strictly`() {
        // Only the long text argument gets the salvaged reading. A path is short enough that
        // a model which mangled the envelope around it probably mangled the value too.
        val json = """{"path":"notes/todo.md","content":"x"}"""

        assertThat(call(json).textArgument("path")).isEqualTo("notes/todo.md")
    }

    @Test
    fun `a flag is true only when the model said true`() {
        fun call(args: String) = ToolCall(id = "1", name = "write_file", argumentsJson = args)

        // The shapes that actually arrive: JSON from the harness, Pythonic from the model.
        assertThat(call("""{"path":"a.js","replace":true}""").flag("replace")).isTrue()
        assertThat(call("""{"path":"a.js","replace":"true"}""").flag("replace")).isTrue()
        assertThat(call("""{"path":"a.js","overwrite":true}""").flag("replace", "overwrite"))
            .isTrue()

        // Everything else is false, because this flag lets a model destroy a file and the
        // default has to be the safe one when the call is ambiguous.
        assertThat(call("""{"path":"a.js","replace":false}""").flag("replace")).isFalse()
        assertThat(call("""{"path":"a.js"}""").flag("replace")).isFalse()
        assertThat(call("""{"path":"a.js","replace":"maybe"}""").flag("replace")).isFalse()
        assertThat(call("not json at all").flag("replace")).isFalse()
    }

    @Test
    fun `a flag-looking sentence inside content cannot authorize replacement`() {
        val call = call(
            """{"path":"notes.md","content":"example: \"replace\": true"}""",
        )

        assertThat(call.flag("replace")).isFalse()
    }

    @Test
    fun `top-level false wins over flag-looking content`() {
        val call = call(
            """{"content":"\"replace\": true","replace":false,"path":"notes.md"}""",
        )

        assertThat(call.flag("replace")).isFalse()
    }
}

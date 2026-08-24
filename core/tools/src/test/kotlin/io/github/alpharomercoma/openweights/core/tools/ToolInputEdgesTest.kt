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
 * What a tool does with input it did not expect.
 *
 * Every case here came from an audit rather than from a bug report, which is the point of
 * having one: these are the shapes a small model produces occasionally and a hostile page
 * produces deliberately, and none of them had a test.
 */
class ToolInputEdgesTest {
    private fun call(arguments: String) =
        ToolCall(id = "1", name = "web_search", argumentsJson = arguments)

    @Test
    fun `an argument that is an object is refused rather than thrown over`() {
        // `.jsonPrimitive` raises on an object instead of returning null, and the raise
        // escaped the tool and ended the turn. A model writing a nested argument is common
        // enough that this has to be an answer, not a crash.
        val nested = call("""{"query": {"text": "kotlin coroutines"}}""")

        assertThat(nested.argument("query")).isNull()
    }

    @Test
    fun `an argument that is an array is refused the same way`() {
        assertThat(call("""{"url": ["https://example.com"]}""").argument("url")).isNull()
    }

    @Test
    fun `an ordinary string argument still reads`() {
        // The counterweight: refusing awkward shapes must not refuse the normal one.
        assertThat(call("""{"query": "tides in Manila"}""").argument("query")).isEqualTo(
            "tides in Manila",
        )
    }

    @Test
    fun `arguments that are not JSON at all yield null rather than raising`() {
        assertThat(call("not json").argument("query")).isNull()
        assertThat(call("").argument("query")).isNull()
    }

    @Test
    fun `a number argument is read as its text`() {
        // Models write intervals both ways, and the watch tool depends on this.
        assertThat(call("""{"every_minutes": 5}""").argument("every_minutes")).isEqualTo("5")
    }

    @Test
    fun `a question mark in a search pattern matches exactly one character`() {
        // It matched any number, because the matcher split on both wildcards and rejoined
        // with one replacement, so `doc_??.txt` also matched `doc_anything_at_all.txt`.
        val matcher = "doc_??.txt".asNameMatcher()

        assertThat(matcher("doc_01.txt")).isTrue()
        assertThat(matcher("doc_ab.txt")).isTrue()
        assertThat(matcher("doc_1.txt")).isFalse()
        assertThat(matcher("doc_012.txt")).isFalse()
        assertThat(matcher("doc_anything_at_all.txt")).isFalse()
    }

    @Test
    fun `a star still matches any number of characters`() {
        val matcher = "*.csv".asNameMatcher()

        assertThat(matcher("sales.csv")).isTrue()
        assertThat(matcher("a/b/sales.csv")).isTrue()
        assertThat(matcher("sales.txt")).isFalse()
    }

    @Test
    fun `a pattern with no wildcard is a plain substring search`() {
        val matcher = "report".asNameMatcher()

        assertThat(matcher("2026-report-final.pdf")).isTrue()
        assertThat(matcher("REPORT.md")).isTrue()
        assertThat(matcher("summary.md")).isFalse()
    }

    @Test
    fun `a regular expression in a pattern is matched literally`() {
        // A pattern is a glob, not a regex. Without escaping, a dot matched any character
        // and a model searching for "a.txt" would also find "axtxt".
        val matcher = "a.txt".asNameMatcher()

        assertThat(matcher("a.txt")).isTrue()
        assertThat(matcher("axtxt")).isFalse()
    }

    @Test
    fun `many overlapping stars cannot trigger regex backtracking`() {
        val matcher = ("*a".repeat(256) + "b").asNameMatcher()

        assertThat(matcher("a".repeat(256) + "c")).isFalse()
    }

    @Test
    fun `a file named without a directory is still seen in a script`() {
        // The path pattern required a slash, so a script reading a file from the root of the
        // shared folder mentioned nothing this could find, and unless the model had also
        // filled in `files` the file was never handed over.
        val source = "const rows = fs.readFileSync('dataset.csv');"

        assertThat(RunScriptTool.mentionedIn(source)).contains("dataset.csv")
    }

    @Test
    fun `a nested path is still seen`() {
        val source = "fs.readFileSync('data/2026/sales.csv')"

        assertThat(RunScriptTool.mentionedIn(source)).contains("data/2026/sales.csv")
    }

    @Test
    fun `a web address in a script is not mistaken for a file`() {
        val source = """fetch("https://example.com/a.json")"""

        assertThat(RunScriptTool.mentionedIn(source)).isEmpty()
    }
}

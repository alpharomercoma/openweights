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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolCallParserTest {
    @Test
    fun `reads a real LFM2 tool call`() {
        // Captured verbatim from LFM2.5-2.6B on the device.
        val raw = "Let me look that up.<|tool_call_start|>[get_weather(city='Manila')]" +
            "<|tool_call_end|>"

        val parsed = ToolCallParser.parse(raw)

        assertThat(parsed.calls).hasSize(1)
        assertThat(parsed.calls.first().name).isEqualTo("get_weather")
        assertThat(parsed.calls.first().argumentsJson).isEqualTo("""{"city": "Manila"}""")
        // The envelope must not survive into what the user reads.
        assertThat(parsed.text).isEqualTo("Let me look that up.")
    }

    @Test
    fun `converts python literals to JSON types rather than quoting everything`() {
        val raw = "<|tool_call_start|>[search(query='rain', limit=5, exact=True, tag=None)]" +
            "<|tool_call_end|>"

        val arguments = ToolCallParser.parse(raw).calls.single().argumentsJson

        assertThat(arguments).contains("\"query\": \"rain\"")
        assertThat(arguments).contains("\"limit\": 5")
        assertThat(arguments).contains("\"exact\": true")
        assertThat(arguments).contains("\"tag\": null")
    }

    @Test
    fun `does not split arguments on commas inside strings`() {
        val raw = "<|tool_call_start|>[note(text='Manila, Philippines')]<|tool_call_end|>"

        val arguments = ToolCallParser.parse(raw).calls.single().argumentsJson

        assertThat(arguments).isEqualTo("""{"text": "Manila, Philippines"}""")
    }

    @Test
    fun `reads several calls in one envelope`() {
        val raw = "<|tool_call_start|>[a(x=1), b(y='two')]<|tool_call_end|>"

        val calls = ToolCallParser.parse(raw).calls

        assertThat(calls.map { it.name }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `reads the Hermes style JSON envelope`() {
        val raw = "Sure.<tool_call>" +
            """{"name": "get_weather", "arguments": {"city": "Oslo"}}""" +
            "</tool_call>"

        val parsed = ToolCallParser.parse(raw)

        assertThat(parsed.calls.single().name).isEqualTo("get_weather")
        assertThat(parsed.calls.single().argumentsJson).isEqualTo("""{"city": "Oslo"}""")
        assertThat(parsed.text).isEqualTo("Sure.")
    }

    @Test
    fun `escapes what JSON requires, not just quotes`() {
        // A Windows path is ordinary model output and must not produce invalid JSON.
        val raw = """<|tool_call_start|>[open(path='C:\tmp', note='say "hi"')]<|tool_call_end|>"""

        val arguments = ToolCallParser.parse(raw).calls.single().argumentsJson

        assertThat(arguments).contains("""\\tmp""")
        assertThat(arguments).contains("""\"hi\"""")
    }

    @Test
    fun `a brace inside a JSON string does not close the arguments early`() {
        val raw = "x<tool_call>" +
            """{"name": "note", "arguments": {"text": "a } brace"}}""" +
            "</tool_call>"

        val arguments = ToolCallParser.parse(raw).calls.single().argumentsJson

        assertThat(arguments).isEqualTo("""{"text": "a } brace"}""")
    }

    @Test
    fun `ordinary prose is left alone`() {
        val raw = "The weather in Manila is warm and humid."

        val parsed = ToolCallParser.parse(raw)

        assertThat(parsed.calls).isEmpty()
        assertThat(parsed.text).isEqualTo(raw)
    }

    @Test
    fun `an unterminated envelope is not treated as a call`() {
        // Generation cut short mid-call must not produce a half-formed invocation.
        val raw = "Looking it up.<|tool_call_start|>[get_weather(city='Man"

        val parsed = ToolCallParser.parse(raw)

        assertThat(parsed.calls).isEmpty()
        assertThat(parsed.text).isEqualTo(raw)
    }

    @Test
    fun `the xml form of the hermes tags is a call, not prose`() {
        // Seen on a phone: the model asked to read a page in this form, no parser knew it,
        // so nothing ran and the markup was shown as the answer.
        val raw = """
            Let me read one of these pages.</think>

            <tool_call>
            <function=fetch_url>
            <parameter=url>
            https://example.com/a
            </parameter>
            </function>
            </tool_call>
        """.trimIndent()

        val parsed = ToolCallParser.parse(raw)

        assertThat(parsed.calls).hasSize(1)
        assertThat(parsed.calls.single().name).isEqualTo("fetch_url")
        // The value sits on its own line between the tags; a URL with a newline is not one.
        assertThat(parsed.calls.single().argumentsJson)
            .isEqualTo("""{"url": "https://example.com/a"}""")
        assertThat(parsed.text).doesNotContain("<tool_call>")
        assertThat(parsed.text).doesNotContain("<function=")
    }

    @Test
    fun `the json form still wins over the xml one`() {
        val raw = """<tool_call>{"name": "web_search", "arguments": {"query": "a"}}</tool_call>"""

        val parsed = ToolCallParser.parse(raw)

        assertThat(parsed.calls.single().name).isEqualTo("web_search")
        assertThat(parsed.calls.single().argumentsJson).contains("query")
    }

    @Test
    fun `tags with neither json nor a function tag stay prose`() {
        // Better to show text we did not understand than to invent a call from it.
        val raw = "<tool_call>something unrecognised</tool_call>"

        assertThat(ToolCallParser.parse(raw).calls).isEmpty()
    }

    @Test
    fun `llama's bare json object is a call`() {
        val raw = """{"name": "web_search", "parameters": {"query": "Manila weather"}}"""

        val parsed = ToolCallParser.parse(raw)

        assertThat(parsed.calls.single().name).isEqualTo("web_search")
        assertThat(parsed.calls.single().argumentsJson).contains("Manila weather")
        assertThat(parsed.text).isEmpty()
    }

    @Test
    fun `json inside an ordinary answer stays prose`() {
        // The bare form has no markers, so only a reply that is nothing but the object
        // can be read as a call; explaining JSON must not trigger one.
        val raw = """Llama calls look like {"name": "f", "parameters": {}} in text."""

        assertThat(ToolCallParser.parse(raw).calls).isEmpty()
    }

    @Test
    fun `llama's python tag does not hide the call behind it`() {
        // Verbatim from a Poco X8 Pro Max run: Llama-3.2-3B-Instruct wrote exactly this
        // and the call inside was correct; only the tag kept it from being parsed.
        val raw = """<|python_tag|>{"name": "get_weather", "parameters": {"city": "Manila"}}"""

        val parsed = ToolCallParser.parse(raw)

        assertThat(parsed.calls.single().name).isEqualTo("get_weather")
        assertThat(parsed.calls.single().argumentsJson).contains("Manila")
    }

    @Test
    fun `two envelopes in one reply are two calls`() {
        val raw = """<tool_call>{"name": "web_search", "arguments": {"query": "a"}}</tool_call>
<tool_call>{"name": "get_weather", "arguments": {"city": "Manila"}}</tool_call>"""

        val parsed = ToolCallParser.parse(raw)

        assertThat(parsed.calls.map { it.name })
            .containsExactly("web_search", "get_weather")
            .inOrder()
    }

    @Test
    fun `a bare object without parameters stays prose`() {
        val raw = """{"name": "Alice", "age": 30}"""

        assertThat(ToolCallParser.parse(raw).calls).isEmpty()
    }
}

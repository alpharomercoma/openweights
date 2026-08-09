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
}

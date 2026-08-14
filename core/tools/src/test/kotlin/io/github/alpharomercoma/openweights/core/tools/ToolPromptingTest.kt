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
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import org.junit.Test

/**
 * Reading a call out of a reply that was never meant to carry one.
 *
 * This path exists for the models whose templates drop tool definitions, which on the three
 * families tested here is two of them. Everything it gets comes out of ordinary prose, so
 * these are the shapes a small model actually produces: the object on its own, the object
 * with an apology in front of it, and the sentence that mentions a tool without calling it.
 *
 * The last one is the one that matters, and refusing it is not a claim that prose can never
 * be a decision. It is a parser saying no so that the layer which can weigh the question
 * gets to weigh it.
 */
class ToolPromptingTest {
    private val registry = ToolRegistry(
        listOf(
            tool("web_search", """{"type":"object","properties":{"query":{"type":"string"}}}"""),
            tool("read_file", """{"type":"object","properties":{"path":{"type":"string"}}}"""),
        ),
    )

    private fun tool(called: String, schema: String) = object : Tool {
        override val definition = ToolDefinition(called, "Does $called.", schema)
        override suspend fun run(call: ToolCall): String = "ran"
    }

    @Test
    fun `the object on its own is a call`() {
        val reply = """{"tool": "web_search", "arguments": {"query": "manila weather"}}"""

        val call = ToolPrompting.parse(reply, registry)

        assertThat(call?.name).isEqualTo("web_search")
        assertThat(call?.argumentsJson).contains("manila weather")
    }

    @Test
    fun `an object with talk around it is still a call`() {
        // Told to send only the object, a small model sends a sentence as well. That is not
        // a reason to refuse it.
        val reply = """
            Sure, let me look that up for you.
            {"tool": "web_search", "arguments": {"query": "wimbledon final"}}
        """.trimIndent()

        val call = ToolPrompting.parse(reply, registry)

        assertThat(call?.name).isEqualTo("web_search")
        assertThat(call?.argumentsJson).contains("wimbledon")
    }

    @Test
    fun `merely naming a tool is not calling one`() {
        // Not because prose can never be a decision. TurnRunner's salvage path decides that
        // it sometimes is, under conditions this parser cannot see: whether the tool was
        // offered at all, whether its reach is the model's to choose, and whether any other
        // tool could have been meant. Refusing here is what leaves that decision where it can
        // be made properly, rather than a parser guessing first and pre-empting it.
        val reply = "I could use web_search for that, but I already know the answer."

        assertThat(ToolPrompting.parse(reply, registry)).isNull()
    }

    @Test
    fun `a tool that does not exist is not invented`() {
        val reply = """{"tool": "send_email", "arguments": {"to": "someone"}}"""

        assertThat(ToolPrompting.parse(reply, registry)).isNull()
    }

    @Test
    fun `nested arguments survive the brace matching`() {
        val reply = """{"tool": "read_file", "arguments": {"path": "a.md", "opts": {"n": 1}}}"""

        val call = ToolPrompting.parse(reply, registry)

        assertThat(call?.argumentsJson).isEqualTo("""{"path": "a.md", "opts": {"n": 1}}""")
    }

    @Test
    fun `a call cut off mid arguments is not dispatched with empty ones`() {
        // It used to come back as a call with no arguments, which spends a round on a tool
        // that can only answer "you gave me nothing" and then tells the model its own
        // truncation was a missing argument. The name said the arguments gave back nothing;
        // what it actually did was dispatch.
        val reply = """{"tool": "web_search", "arguments": {"query": "unterminated"""

        assertThat(ToolPrompting.parse(reply, registry)).isNull()
    }

    @Test
    fun `a tool that takes no arguments is still callable`() {
        // The other branch, and the reason the rule is about a truncated object rather than
        // about an absent one: no arguments key at all is a tool that needs none.
        val reply = """{"tool": "web_search"}"""

        assertThat(ToolPrompting.parse(reply, registry)?.argumentsJson).isEqualTo("{}")
    }

    @Test
    fun `the envelope the tuning data uses is read too`() {
        // Hermes wraps the object in tags and names the key "name" rather than "tool", and so
        // does a large share of the instruction data these models were tuned on. Refusing that
        // spelling means refusing the call a model actually made, whatever the prompt asked
        // for. The tags need no handling of their own: the object is found by its keys and
        // taken by matching braces, so what surrounds it never comes into it.
        val reply = """<tool_call>{"name": "web_search", """ +
            """"arguments": {"query": "ada"}}</tool_call>"""

        val call = ToolPrompting.parse(reply, registry)

        assertThat(call?.name).isEqualTo("web_search")
        assertThat(call?.argumentsJson).contains("ada")
    }

    @Test
    fun `a nested function wrapper is read from the same key`() {
        // The other spelling in the wild. The name is in the same place, so the walk that
        // finds the arguments finds these too.
        val reply = """{"type": "function", "function": {"name": "read_file", """ +
            """"arguments": {"path": "a.md"}}}"""

        assertThat(ToolPrompting.parse(reply, registry)?.name).isEqualTo("read_file")
    }

    @Test
    fun `the tagged format is what the tagged prompt asks for`() {
        val described = ToolPrompting.describe(registry.definitions, CallFormat.TAGGED)

        assertThat(described).contains("<tool_call>")
        assertThat(described).contains("web_search")
        // And the default is still the bare object, so nothing shipped changed with it.
        assertThat(ToolPrompting.describe(registry.definitions)).doesNotContain("<tool_call>")
    }

    @Test
    fun `an ordinary answer is left alone`() {
        assertThat(ToolPrompting.parse("The capital of France is Paris.", registry)).isNull()
    }

    @Test
    fun `every tool is described with its schema`() {
        val described = ToolPrompting.describe(registry.definitions)

        assertThat(described).contains("web_search")
        assertThat(described).contains("read_file")
        assertThat(described).contains("\"query\"")
        // One line each, because a pretty-printed schema is only longer.
        assertThat(described).doesNotContain("\n  ")
    }

    @Test
    fun `no tools means nothing is added to the prompt`() {
        assertThat(ToolPrompting.describe(emptyList())).isEmpty()
    }
}

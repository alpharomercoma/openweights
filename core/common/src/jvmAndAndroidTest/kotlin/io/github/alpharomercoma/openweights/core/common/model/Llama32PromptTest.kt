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

/**
 * Holds [Llama32Prompt] to what Llama 3.2's own template produces, byte for byte.
 *
 * The fixtures are pinned to the template's documented fallback date, and every render
 * here passes the same one; at runtime the adapter passes today's.
 */
class Llama32PromptTest {

    @Test
    fun `always opens with a dated system block`() {
        val rendered = render(listOf(user("What is the capital of Japan?")))

        assertThat(rendered).isEqualTo(Llama32PromptFixtures.PLAIN)
    }

    @Test
    fun `splices a leading system message under the dates`() {
        val rendered = render(
            listOf(system("You are a terse assistant."), user("What is the capital of Japan?")),
        )

        assertThat(rendered).isEqualTo(Llama32PromptFixtures.WITH_SYSTEM)
    }

    @Test
    fun `puts tool schemas in the first user turn, indented four deep`() {
        val rendered = render(listOf(user("What is the weather in Manila?")), TOOLS)

        assertThat(rendered).isEqualTo(Llama32PromptFixtures.WITH_TOOLS)
        assertThat(rendered).contains("Environment: ipython")
    }

    @Test
    fun `a stored call renders exactly as the template writes one`() {
        // The app stores the assistant turn as the raw JSON the model emitted; upstream
        // renders it from a structured call. The two agreeing is the point.
        val rendered = render(
            listOf(
                user("What is the weather in Manila?"),
                assistant(
                    "{\"name\": \"web_search\", \"parameters\": {\"query\": \"Manila weather\"}}",
                ),
            ),
            TOOLS,
        )

        assertThat(rendered).isEqualTo(Llama32PromptFixtures.TOOL_CALL)
    }

    @Test
    fun `feeds a plain-text result back JSON-quoted under ipython`() {
        val rendered = render(
            listOf(
                user("What is the weather in Manila?"),
                assistant(
                    "{\"name\": \"web_search\", \"parameters\": {\"query\": \"Manila weather\"}}",
                ),
                toolResult("Manila: 31C, humid."),
            ),
            TOOLS,
        )

        assertThat(rendered).isEqualTo(Llama32PromptFixtures.TOOL_RUN)
        assertThat(rendered).contains("\"Manila: 31C, humid.\"")
    }

    @Test
    fun `renders a whole conversation`() {
        val rendered = render(
            listOf(user("What is 2+2?"), assistant("Four."), user("And 3+3?")),
        )

        assertThat(rendered).isEqualTo(Llama32PromptFixtures.MULTI_TURN)
    }

    @Test
    fun `a schema with escaped quotes survives the reindent`() {
        // codex QA: the walker cleared its escape state before deciding whether a quote
        // closed the string, so \" ended it and later punctuation became structure.
        val tricky = ToolDefinition(
            name = "web_search",
            description = """Search for "café: météo", quoted.""",
            parametersJson = """{"type": "object", "properties": {"q": """ +
                """{"type": "string", "description": "say \"hi\", then: go"}}, """ +
                """"required": ["q"]}""",
        )

        val rendered = render(listOf(user("Weather?")), listOf(tricky))

        assertThat(rendered).contains("""say \"hi\", then: go""")
    }

    private fun render(messages: List<ChatMessage>, tools: List<ToolDefinition> = emptyList()) =
        Llama32Prompt.render(messages, tools, date = FIXTURE_DATE)

    private fun system(text: String) = ChatMessage.text(ChatRole.SYSTEM, text)
    private fun user(text: String) = ChatMessage.text(ChatRole.USER, text)
    private fun assistant(text: String) = ChatMessage.text(ChatRole.ASSISTANT, text)
    private fun toolResult(text: String) = ChatMessage.toolResult("web_search", text)

    private companion object {
        const val FIXTURE_DATE = "26 Jul 2024"

        val TOOLS = listOf(
            ToolDefinition(
                name = "web_search",
                description = "Search the web for current information.",
                parametersJson = "{\"type\": \"object\", \"properties\": {\"query\": " +
                    "{\"type\": \"string\", \"description\": \"What to search for\"}}, " +
                    "\"required\": [\"query\"]}",
            ),
        )
    }
}

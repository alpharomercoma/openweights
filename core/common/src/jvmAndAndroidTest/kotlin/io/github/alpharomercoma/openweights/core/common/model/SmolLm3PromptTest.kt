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
 * Holds [SmolLm3Prompt] to what SmolLM3's own template produces, byte for byte.
 *
 * Two assertions here document upstream behaviour rather than ours: without tools the
 * system block is never closed with `<|im_end|>`, and a system message that is not first
 * disappears. Both are the template as shipped, and every transformers user gets the same.
 */
class SmolLm3PromptTest {

    @Test
    fun `opens with metadata and the default thinking instructions`() {
        val rendered = render(listOf(user("What is the capital of Japan?")))

        assertThat(rendered).isEqualTo(SmolLm3PromptFixtures.PLAIN)
        assertThat(rendered).contains("Reasoning Mode: /think")
    }

    @Test
    fun `thinking off closes an empty think block ahead of the reply`() {
        val rendered = render(listOf(user("What is the capital of Japan?")), thinking = false)

        assertThat(rendered).isEqualTo(SmolLm3PromptFixtures.PLAIN_NO_THINK)
        assertThat(rendered).contains("Reasoning Mode: /no_think")
    }

    @Test
    fun `custom instructions replace the default ones under the metadata`() {
        val rendered = render(
            listOf(system("You are a terse assistant."), user("What is the capital of Japan?")),
        )

        assertThat(rendered).isEqualTo(SmolLm3PromptFixtures.WITH_SYSTEM)
    }

    @Test
    fun `system_override replaces the whole scaffold`() {
        val rendered = render(
            listOf(
                system("You are a terse assistant. /system_override"),
                user("What is the capital of Japan?"),
            ),
        )

        assertThat(rendered).isEqualTo(SmolLm3PromptFixtures.SYSTEM_OVERRIDE)
        assertThat(rendered).doesNotContain("## Metadata")
    }

    @Test
    fun `renders tools and closes the system block only then`() {
        val rendered = render(listOf(user("What is the weather in Manila?")), TOOLS)

        assertThat(rendered).isEqualTo(SmolLm3PromptFixtures.WITH_TOOLS)
    }

    @Test
    fun `renders a whole conversation`() {
        val rendered = render(
            listOf(user("What is 2+2?"), assistant("Four."), user("And 3+3?")),
        )

        assertThat(rendered).isEqualTo(SmolLm3PromptFixtures.MULTI_TURN)
    }

    @Test
    fun `a tool result renders as a user turn`() {
        val rendered = render(
            listOf(
                user("What is the weather in Manila?"),
                assistant(
                    "<tool_call>\n{\"name\": \"web_search\", " +
                        "\"arguments\": {\"query\": \"Manila weather\"}}\n</tool_call>",
                ),
                toolResult("Manila: 31C, humid."),
            ),
            TOOLS,
        )

        assertThat(rendered).isEqualTo(SmolLm3PromptFixtures.TOOL_RUN)
    }

    private fun render(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        thinking: Boolean = true,
    ) = SmolLm3Prompt.render(messages, tools, thinking, date = FIXTURE_DATE)

    private fun system(text: String) = ChatMessage.text(ChatRole.SYSTEM, text)
    private fun user(text: String) = ChatMessage.text(ChatRole.USER, text)
    private fun assistant(text: String) = ChatMessage.text(ChatRole.ASSISTANT, text)
    private fun toolResult(text: String) = ChatMessage.toolResult("web_search", text)

    private companion object {
        const val FIXTURE_DATE = "29 August 2026"

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

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
 * Holds [Lfm25Prompt] to what LFM 2.5's own template produces, byte for byte.
 */
class Lfm25PromptTest {

    @Test
    fun `renders a bare conversation with no system block at all`() {
        val rendered = Lfm25Prompt.render(listOf(user("What is the capital of Japan?")))

        assertThat(rendered).isEqualTo(Lfm25PromptFixtures.PLAIN)
    }

    @Test
    fun `renders a leading system message`() {
        val rendered = Lfm25Prompt.render(
            listOf(system("You are a terse assistant."), user("What is the capital of Japan?")),
        )

        assertThat(rendered).isEqualTo(Lfm25PromptFixtures.WITH_SYSTEM)
    }

    @Test
    fun `lists tools inside the system turn`() {
        val rendered = Lfm25Prompt.render(listOf(user("What is the weather in Manila?")), TOOLS)

        assertThat(rendered).isEqualTo(Lfm25PromptFixtures.WITH_TOOLS)
        assertThat(rendered).contains("List of tools: [")
    }

    @Test
    fun `keeps thinking only in the last assistant turn`() {
        val rendered = Lfm25Prompt.render(
            listOf(
                user("What is 2+2?"),
                assistant("<think>Simple arithmetic.</think>Four."),
                user("And 3+3?"),
                assistant("<think>Also simple.</think>Six."),
            ),
            addGenerationPrompt = false,
        )

        assertThat(rendered).isEqualTo(Lfm25PromptFixtures.PRIOR_THINKING_DROPPED)
        assertThat(rendered).doesNotContain("Simple arithmetic")
        assertThat(rendered).contains("Also simple")
    }

    @Test
    fun `verbatim history keeps every turn's thinking for the cache`() {
        val rendered = Lfm25Prompt.render(
            listOf(
                user("What is 2+2?"),
                assistant("<think>Simple arithmetic.</think>Four."),
                user("And 3+3?"),
            ),
            verbatimHistory = true,
        )

        assertThat(rendered).contains("Simple arithmetic")
    }

    @Test
    fun `renders a pythonic call and its tool-role result`() {
        val rendered = Lfm25Prompt.render(
            listOf(
                user("What is the weather in Manila?"),
                assistant(
                    "<|tool_call_start|>[web_search(query='Manila weather')]<|tool_call_end|>",
                ),
                toolResult("Manila: 31C, humid."),
            ),
            TOOLS,
        )

        assertThat(rendered).isEqualTo(Lfm25PromptFixtures.TOOL_RUN)
    }

    private fun system(text: String) = ChatMessage.text(ChatRole.SYSTEM, text)
    private fun user(text: String) = ChatMessage.text(ChatRole.USER, text)
    private fun assistant(text: String) = ChatMessage.text(ChatRole.ASSISTANT, text)
    private fun toolResult(text: String) = ChatMessage.toolResult("web_search", text)

    private companion object {
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

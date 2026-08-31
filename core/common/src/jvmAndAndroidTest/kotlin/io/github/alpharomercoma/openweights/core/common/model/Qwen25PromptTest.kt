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
 * Holds [Qwen25Prompt] to what Qwen2.5's own template produces, byte for byte.
 */
class Qwen25PromptTest {

    @Test
    fun `falls back to the default system prompt`() {
        val rendered = Qwen25Prompt.render(listOf(user("What is the capital of Japan?")))

        assertThat(rendered).isEqualTo(Qwen25PromptFixtures.PLAIN)
        assertThat(rendered).contains("created by Alibaba Cloud")
    }

    @Test
    fun `keeps a leading system message instead of the default`() {
        val rendered = Qwen25Prompt.render(
            listOf(system("You are a terse assistant."), user("What is the capital of Japan?")),
        )

        assertThat(rendered).isEqualTo(Qwen25PromptFixtures.WITH_SYSTEM)
    }

    @Test
    fun `renders tools under the default system prompt`() {
        val rendered = Qwen25Prompt.render(listOf(user("What is the weather in Manila?")), TOOLS)

        assertThat(rendered).isEqualTo(Qwen25PromptFixtures.WITH_TOOLS)
    }

    @Test
    fun `keeps the system message above the tools when both are present`() {
        val rendered = Qwen25Prompt.render(
            listOf(system("You are a terse assistant."), user("What is the weather in Manila?")),
            TOOLS,
        )

        assertThat(rendered).isEqualTo(Qwen25PromptFixtures.WITH_SYSTEM_AND_TOOLS)
    }

    @Test
    fun `renders a call and its result the way the model was trained`() {
        val rendered = Qwen25Prompt.render(
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

        assertThat(rendered).isEqualTo(Qwen25PromptFixtures.TOOL_RUN)
    }

    @Test
    fun `collapses consecutive tool results into one user turn`() {
        val rendered = Qwen25Prompt.render(
            listOf(
                user("Compare Manila and Tokyo."),
                assistant("Looking both up."),
                toolResult("Manila: 31C."),
                toolResult("Tokyo: 22C."),
            ),
            TOOLS,
        )

        assertThat(rendered).isEqualTo(Qwen25PromptFixtures.TWO_TOOL_RESULTS)
        assertThat(rendered.split("<|im_start|>user").size - 1).isEqualTo(2)
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

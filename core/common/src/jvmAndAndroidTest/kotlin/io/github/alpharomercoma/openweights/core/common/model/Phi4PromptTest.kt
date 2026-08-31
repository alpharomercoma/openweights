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
 * Holds [Phi4Prompt] to what Phi-4-mini's own template produces, byte for byte.
 */
class Phi4PromptTest {

    @Test
    fun `renders a bare conversation with no system turn at all`() {
        val rendered = Phi4Prompt.render(listOf(user("What is the capital of Japan?")))

        assertThat(rendered).isEqualTo(Phi4PromptFixtures.PLAIN)
    }

    @Test
    fun `renders a leading system message`() {
        val rendered = Phi4Prompt.render(
            listOf(system("You are a terse assistant."), user("What is the capital of Japan?")),
        )

        assertThat(rendered).isEqualTo(Phi4PromptFixtures.WITH_SYSTEM)
    }

    @Test
    fun `carries tools on the system turn between tool markers`() {
        val rendered = Phi4Prompt.render(
            listOf(system("You are a terse assistant."), user("What is the weather in Manila?")),
            TOOLS,
        )

        assertThat(rendered).isEqualTo(Phi4PromptFixtures.WITH_TOOLS)
    }

    @Test
    fun `synthesises an empty system turn when tools have nowhere to ride`() {
        val rendered = Phi4Prompt.render(listOf(user("What is the weather in Manila?")), TOOLS)

        assertThat(rendered).isEqualTo(Phi4PromptFixtures.WITH_TOOLS_NO_SYSTEM)
    }

    @Test
    fun `renders a whole conversation without separators between turns`() {
        val rendered = Phi4Prompt.render(
            listOf(user("What is 2+2?"), assistant("Four."), user("And 3+3?")),
        )

        assertThat(rendered).isEqualTo(Phi4PromptFixtures.MULTI_TURN)
    }

    private fun system(text: String) = ChatMessage.text(ChatRole.SYSTEM, text)
    private fun user(text: String) = ChatMessage.text(ChatRole.USER, text)
    private fun assistant(text: String) = ChatMessage.text(ChatRole.ASSISTANT, text)

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

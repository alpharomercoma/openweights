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
 * Holds [Qwen3Prompt] to what Qwen3's own template produces, byte for byte.
 *
 * Every expected string in [Qwen3PromptFixtures] came out of the real Jinja template, so
 * these are not assertions about what the format ought to be — they are a diff against
 * upstream. That matters more here than for most tests: a chat template that has drifted
 * does not throw. The model keeps answering, slightly worse, and tool calls quietly stop
 * being recognised, which is indistinguishable from the model being bad at its job.
 *
 * Three of these cases exist because the first, hand-written version of [Qwen3Prompt] got
 * them wrong: [SYSTEM_NOT_FIRST], [PRIOR_THINKING_DROPPED] and [TOOL_RUN].
 */
class Qwen3PromptTest {

    @Test
    fun `renders a bare conversation`() {
        val rendered = Qwen3Prompt.render(listOf(user("What is the capital of Japan?")))

        // No system block at all: Qwen3 has no default system prompt, and an empty one
        // would be a difference the model can see.
        assertThat(rendered).isEqualTo(Qwen3PromptFixtures.PLAIN)
    }

    @Test
    fun `renders a leading system message`() {
        val rendered = Qwen3Prompt.render(
            listOf(system("You are a terse assistant."), user("What is the capital of Japan?")),
        )

        assertThat(rendered).isEqualTo(Qwen3PromptFixtures.WITH_SYSTEM)
    }

    @Test
    fun `switches thinking off by closing an empty block in the opener`() {
        val rendered = Qwen3Prompt.render(
            listOf(user("What is the capital of Japan?")),
            thinking = false,
        )

        assertThat(rendered).isEqualTo(Qwen3PromptFixtures.THINKING_DISABLED)
    }

    @Test
    fun `renders tools into a system block when there was no system message`() {
        val rendered = Qwen3Prompt.render(listOf(user("What is the weather in Manila?")), TOOLS)

        assertThat(rendered).isEqualTo(Qwen3PromptFixtures.WITH_TOOLS)
    }

    @Test
    fun `keeps the system message above the tools when both are present`() {
        val rendered = Qwen3Prompt.render(
            listOf(system("You are a terse assistant."), user("What is the weather in Manila?")),
            TOOLS,
        )

        assertThat(rendered).isEqualTo(Qwen3PromptFixtures.WITH_SYSTEM_AND_TOOLS)
    }

    @Test
    fun `renders a system message that is not first as an ordinary turn`() {
        // Hoisting this one, which the hand-written version did, both moved it above the
        // conversation and dropped it from where it belonged.
        val rendered = Qwen3Prompt.render(
            listOf(
                user("Hello."),
                system("Be brief from now on."),
                user("What is the capital of Japan?"),
            ),
        )

        assertThat(rendered).isEqualTo(Qwen3PromptFixtures.SYSTEM_NOT_FIRST)
    }

    @Test
    fun `drops thinking from turns before the user's last question`() {
        val rendered = Qwen3Prompt.render(
            listOf(
                user("What is 2+2?"),
                assistant("<think>\nSimple arithmetic.\n</think>\n\nFour."),
                user("And 3+3?"),
            ),
        )

        assertThat(rendered).isEqualTo(Qwen3PromptFixtures.PRIOR_THINKING_DROPPED)
        assertThat(rendered).doesNotContain("Simple arithmetic")
    }

    @Test
    fun `keeps thinking inside the run that is still going`() {
        // The app stores an assistant turn as the raw text the model produced, so the call
        // markup is already in the content rather than in a field of its own. The fixture
        // was rendered from the structured form upstream expects, and the two agreeing is
        // the point: our history reaches the model in the shape it was trained on.
        val rendered = Qwen3Prompt.render(
            listOf(
                user("What is the weather in Manila?"),
                assistant(
                    "<think>\nI should look this up.\n</think>\n\n" +
                        "<tool_call>\n{\"name\": \"web_search\", " +
                        "\"arguments\": {\"query\": \"Manila weather\"}}\n</tool_call>",
                ),
                toolResult("Manila: 31C, humid."),
            ),
            TOOLS,
        )

        assertThat(rendered).isEqualTo(Qwen3PromptFixtures.TOOL_RUN)
        // The reasoning survives, because the model is mid-run and about to build on it.
        assertThat(rendered).contains("I should look this up.")
    }

    @Test
    fun `collapses consecutive tool results into one user turn`() {
        val rendered = Qwen3Prompt.render(
            listOf(
                user("Compare Manila and Tokyo."),
                assistant("Looking both up."),
                toolResult("Manila: 31C."),
                toolResult("Tokyo: 22C."),
            ),
            TOOLS,
        )

        assertThat(rendered).isEqualTo(Qwen3PromptFixtures.TWO_TOOL_RESULTS)
        // Two responses, one turn: the second must not open a user block of its own.
        assertThat(rendered.split("<|im_start|>user").size - 1).isEqualTo(2)
    }

    private fun system(text: String) = ChatMessage.text(ChatRole.SYSTEM, text)
    private fun user(text: String) = ChatMessage.text(ChatRole.USER, text)
    private fun assistant(text: String) = ChatMessage.text(ChatRole.ASSISTANT, text)
    private fun toolResult(text: String) = ChatMessage.toolResult("web_search", text)

    private companion object {
        /**
         * The same tool the reference renderer describes.
         *
         * The schema is spelled exactly as `json.dumps` writes it, because [Qwen3Prompt]
         * splices it in verbatim rather than re-encoding it. Different spacing here would
         * fail the comparison for a reason that has nothing to do with the template.
         */
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

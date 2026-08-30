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
 * Holds [SmolLm2Prompt] to what SmolLM2's own template produces, byte for byte.
 *
 * Every expected string in [SmolLm2PromptFixtures] came out of the real Jinja template,
 * so these are a diff against upstream rather than an opinion about the format.
 */
class SmolLm2PromptTest {

    @Test
    fun `injects the default system prompt when none is given`() {
        val rendered = SmolLm2Prompt.render(listOf(user("What is the capital of Japan?")))

        assertThat(rendered).isEqualTo(SmolLm2PromptFixtures.PLAIN)
    }

    @Test
    fun `keeps a leading system message instead of the default`() {
        val rendered = SmolLm2Prompt.render(
            listOf(system("You are a terse assistant."), user("What is the capital of Japan?")),
        )

        assertThat(rendered).isEqualTo(SmolLm2PromptFixtures.WITH_SYSTEM)
        assertThat(rendered).doesNotContain("named SmolLM")
    }

    @Test
    fun `renders a whole conversation as ChatML turns`() {
        val rendered = SmolLm2Prompt.render(
            listOf(user("What is 2+2?"), assistant("Four."), user("And 3+3?")),
        )

        assertThat(rendered).isEqualTo(SmolLm2PromptFixtures.MULTI_TURN)
    }

    @Test
    fun `a later system message is an ordinary turn and the default still applies`() {
        val rendered = SmolLm2Prompt.render(
            listOf(
                user("Hello."),
                system("Be brief from now on."),
                user("What is the capital of Japan?"),
            ),
        )

        assertThat(rendered).isEqualTo(SmolLm2PromptFixtures.SYSTEM_NOT_FIRST)
    }

    private fun system(text: String) = ChatMessage.text(ChatRole.SYSTEM, text)
    private fun user(text: String) = ChatMessage.text(ChatRole.USER, text)
    private fun assistant(text: String) = ChatMessage.text(ChatRole.ASSISTANT, text)
}

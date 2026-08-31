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
 * Holds [Gemma3Prompt] to what Gemma 3's own template produces, byte for byte.
 */
class Gemma3PromptTest {

    @Test
    fun `renders a bare conversation`() {
        val rendered = Gemma3Prompt.render(listOf(user("What is the capital of Japan?")))

        assertThat(rendered).isEqualTo(Gemma3PromptFixtures.PLAIN)
    }

    @Test
    fun `folds a leading system message into the first user turn`() {
        val rendered = Gemma3Prompt.render(
            listOf(system("You are a terse assistant."), user("What is the capital of Japan?")),
        )

        assertThat(rendered).isEqualTo(Gemma3PromptFixtures.WITH_SYSTEM)
        // No system role exists in this format; the message must survive anyway.
        assertThat(rendered).doesNotContain("system")
    }

    @Test
    fun `calls the assistant model and renders turns alternately`() {
        val rendered = Gemma3Prompt.render(
            listOf(user("What is 2+2?"), assistant("Four."), user("And 3+3?")),
        )

        assertThat(rendered).isEqualTo(Gemma3PromptFixtures.MULTI_TURN)
    }

    @Test
    fun `the engine's rendering only differs by the missing BOS`() {
        val withBos = Gemma3Prompt.render(listOf(user("Hi.")))
        val without = Gemma3Prompt.render(listOf(user("Hi.")), includeBos = false)

        assertThat("<bos>" + without).isEqualTo(withBos)
    }

    private fun system(text: String) = ChatMessage.text(ChatRole.SYSTEM, text)
    private fun user(text: String) = ChatMessage.text(ChatRole.USER, text)
    private fun assistant(text: String) = ChatMessage.text(ChatRole.ASSISTANT, text)
}

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

package io.github.alpharomercoma.openweights.ui.chat

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import org.junit.Test

/**
 * Plan mode's instruction no longer promises something the prompt contradicts.
 *
 * It used to say "You have tools available. Do not call them" while being handed the whole
 * catalogue, and measured on five ambiguous requests the model called one anyway two times
 * in five on the 2.6B and four in five on the 1.2B. The tools are now withheld instead, so
 * the sentence about not calling them has nothing to describe and is gone.
 */
class PlanModeToolsTest {
    @Test
    fun `plan mode does not tell the model it has tools it will not be given`() {
        val instruction = planInstruction(anyTools = true)

        assertThat(instruction).isNotNull()
        assertThat(instruction).doesNotContain("Do not call them")
        assertThat(instruction).doesNotContain("You have tools available")
    }

    @Test
    fun `plan mode asks for a question when the request is ambiguous`() {
        assertThat(planInstruction(anyTools = true)).contains("ask before planning")
    }

    @Test
    fun `with every tool switched off it still says what the mode is`() {
        val instruction = planInstruction(anyTools = false)

        assertThat(instruction).isNotNull()
        assertThat(instruction).contains("Do not act on anything yet")
    }

    @Test
    fun `the running modes say nothing of their own`() {
        listOf(AgentMode.AUTO, AgentMode.ASK, AgentMode.YOLO).forEach { mode ->
            // They carry the configured tool prompt, which is a setting rather than a mode.
            assertThat(toolInstruction(mode, configured = "", anyTools = true)).isNull()
        }
    }

    private fun planInstruction(anyTools: Boolean) =
        toolInstruction(AgentMode.PLAN, configured = "anything", anyTools = anyTools)
}

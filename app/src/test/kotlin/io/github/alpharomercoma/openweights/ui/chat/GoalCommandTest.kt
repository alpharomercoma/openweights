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
 * The one command that carries a message with it.
 *
 * Every other command is a whole message and nothing else, so recognising one is an equality
 * check. `/goal` is a trigger followed by the task, which is a different shape and the place
 * a parser goes wrong: too eager and "/goalkeeper training" starts a goal, too strict and
 * the task is thrown away.
 */
class GoalCommandTest {
    @Test
    fun `a goal is recognised with its task and the task comes back whole`() {
        val typed = "/goal pull the September dates out of my notes"

        assertThat(SlashCommand.typed(typed)).isEqualTo(SlashCommand.GOAL)
        assertThat(SlashCommand.argument(typed))
            .isEqualTo("pull the September dates out of my notes")
    }

    @Test
    fun `the trigger on its own is still the command, with nothing to do`() {
        assertThat(SlashCommand.typed("/goal")).isEqualTo(SlashCommand.GOAL)
        assertThat(SlashCommand.argument("/goal")).isEmpty()
    }

    @Test
    fun `a word that merely starts with the trigger is not the command`() {
        // No space, so it is one word and one word is not a command with an argument.
        assertThat(SlashCommand.typed("/goalkeeper training plan")).isNull()
        assertThat(SlashCommand.argument("/goalkeeper training plan")).isEmpty()
    }

    @Test
    fun `no other command takes an argument`() {
        SlashCommand.entries.filter { it != SlashCommand.GOAL }.forEach { command ->
            assertThat(command.takesArgument).isFalse()
            // Which is why a sentence beginning with one is still a sentence.
            assertThat(SlashCommand.typed("${command.trigger} and then some")).isNull()
        }
    }

    @Test
    fun `running it hands the task over and nothing else fires`() {
        val fired = mutableListOf<String>()
        SlashCommand.GOAL.run(
            onNewChat = { fired += "new" },
            onCompact = { fired += "compact" },
            onRegenerate = { fired += "retry" },
            onMode = { fired += "mode:${it.name}" },
            onGoal = { fired += "goal:$it" },
            argument = "tidy the notes folder",
        )

        assertThat(fired).containsExactly("goal:tidy the notes folder")
    }

    @Test
    fun `the palette still finds it while it is being typed`() {
        listOf("/", "/g", "/go", "/goa", "/goal").forEach { draft ->
            assertThat(SlashCommand.match(draft)).contains(SlashCommand.GOAL)
        }
    }

    @Test
    fun `the other commands still run exactly one action`() {
        // Adding a parameter to run() is the kind of change that silently rewires a when.
        val modes = mapOf(
            SlashCommand.PLAN to AgentMode.PLAN,
            SlashCommand.AUTO to AgentMode.AUTO,
            SlashCommand.ASK to AgentMode.ASK,
            SlashCommand.YOLO to AgentMode.YOLO,
        )
        modes.forEach { (command, mode) ->
            val fired = mutableListOf<String>()
            command.run(
                onNewChat = { fired += "new" },
                onCompact = { fired += "compact" },
                onRegenerate = { fired += "retry" },
                onMode = { fired += "mode:${it.name}" },
                onGoal = { fired += "goal:$it" },
            )
            assertThat(fired).containsExactly("mode:${mode.name}")
        }
    }
}

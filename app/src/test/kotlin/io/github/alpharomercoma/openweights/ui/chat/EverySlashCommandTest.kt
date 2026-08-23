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
 * Every command, found every way it can be found, doing the one thing it says.
 *
 * `run` is an exhaustive `when`, so a command added to the enum and forgotten there will not
 * compile. What that cannot catch is a command wired to the wrong action, or one that the
 * palette finds but a typed message does not, which is the failure that made typing "/plan"
 * send the word to the model as a question.
 *
 * Driven off `entries` rather than a list written out here, so a command added later is
 * covered by this the day it is added or it fails.
 */
class EverySlashCommandTest {
    @Test
    fun `every command is found by its own trigger, whole and by prefix`() {
        SlashCommand.entries.forEach { command ->
            assertThat(SlashCommand.match(command.trigger)).contains(command)
            assertThat(SlashCommand.typed(command.trigger)).isEqualTo(command)
            // Trailing spaces and case are what a real keyboard produces.
            assertThat(SlashCommand.typed(" ${command.trigger.uppercase()} "))
                .isEqualTo(command)

            // Every prefix of the trigger offers it, which is what makes the palette usable
            // before anybody knows the whole word.
            for (length in 1..command.trigger.length) {
                assertThat(SlashCommand.match(command.trigger.take(length))).contains(command)
            }
        }
    }

    @Test
    fun `every command runs exactly one action, and the right one`() {
        SlashCommand.entries.forEach { command ->
            val fired = mutableListOf<String>()
            command.run(
                onNewChat = { fired += "new" },
                onCompact = { fired += "compact" },
                onRegenerate = { fired += "retry" },
                onMode = { fired += "mode:${it.name}" },
                onGoal = { fired += "goal:$it" },
                argument = "a task",
            )

            assertThat(fired).hasSize(1)
            val expected = when (command) {
                SlashCommand.NEW_CHAT -> "new"
                SlashCommand.COMPACT -> "compact"
                SlashCommand.REGENERATE -> "retry"
                SlashCommand.PLAN -> "mode:${AgentMode.PLAN.name}"
                SlashCommand.AUTO -> "mode:${AgentMode.AUTO.name}"
                SlashCommand.ASK -> "mode:${AgentMode.ASK.name}"
                SlashCommand.YOLO -> "mode:${AgentMode.YOLO.name}"
                SlashCommand.GOAL -> "goal:a task"
            }
            assertThat(fired.single()).isEqualTo(expected)
        }
    }

    @Test
    fun `every trigger is distinct, so no two commands answer to the same word`() {
        val triggers = SlashCommand.entries.map { it.trigger.lowercase() }
        assertThat(triggers).containsNoDuplicates()
        assertThat(triggers).containsNoneIn(listOf("/"))
        triggers.forEach { assertThat(it).startsWith("/") }
    }

    @Test
    fun `a sentence that opens with a slash is a sentence`() {
        assertThat(SlashCommand.match("/plan something")).isNull()
        assertThat(SlashCommand.typed("/plan the garden")).isNull()
        assertThat(SlashCommand.typed("tell me about /plan")).isNull()
    }

    @Test
    fun `only the command that takes an argument survives having one`() {
        SlashCommand.entries.forEach { command ->
            val withTail = SlashCommand.typed("${command.trigger} and then some")
            if (command.takesArgument) {
                assertThat(withTail).isEqualTo(command)
            } else {
                assertThat(withTail).isNull()
            }
        }
    }
}

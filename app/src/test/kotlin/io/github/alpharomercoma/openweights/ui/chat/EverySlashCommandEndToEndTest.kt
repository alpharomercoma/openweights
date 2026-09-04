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
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Every command, along the path a typed message actually takes.
 *
 * [SlashCommandTest] proves the parsing and [EverySlashCommandTest] proves that `run`
 * fires one action. Neither joins them, and the join is where the failure that started
 * all of this lived: a command that parsed perfectly and reached the model as prose,
 * because nothing connected the two. So this walks the whole path per command — the text
 * a person types, [submit]'s decision, the dispatch, and the action that comes out — and
 * it is driven off `entries`, so a tenth command is covered the day it is added.
 */
class EverySlashCommandEndToEndTest {

    /** What came out of the path, named the way the callbacks are. */
    private data class Fired(val action: String, val argument: String)

    /** One typed message, all the way through to the action it runs. */
    private fun type(typed: String): Fired? {
        var fired: Fired? = null
        var sentAsProse: String? = null
        val handled = submit(
            typed = typed,
            editingId = null,
            onDispatch = { command, argument ->
                command.run(
                    onNewChat = { fired = Fired("new", argument) },
                    onCompact = { fired = Fired("compact", argument) },
                    onRegenerate = { fired = Fired("retry", argument) },
                    onMode = { fired = Fired("mode:${it.name}", argument) },
                    onGoal = { fired = Fired("goal", it) },
                    onResearch = { fired = Fired("research", it) },
                    argument = argument,
                )
            },
            onEdit = { _, _ -> error("nothing is being edited") },
            onSend = {
                sentAsProse = it
                true
            },
        )
        assertWithMessage("submit did not handle $typed").that(handled).isTrue()
        assertWithMessage("$typed reached the model as prose").that(sentAsProse).isNull()
        return fired
    }

    /** The action each command is supposed to run, as the screen wires them. */
    private val expected = mapOf(
        SlashCommand.NEW_CHAT to "new",
        SlashCommand.COMPACT to "compact",
        SlashCommand.REGENERATE to "retry",
        SlashCommand.PLAN to "mode:PLAN",
        SlashCommand.AUTO to "mode:AUTO",
        SlashCommand.ASK to "mode:ASK",
        SlashCommand.YOLO to "mode:YOLO",
        SlashCommand.GOAL to "goal",
        SlashCommand.DEEP_RESEARCH to "research",
    )

    @Test
    fun `every command has an expected action, so a new one cannot be forgotten here`() {
        assertThat(expected.keys).containsExactlyElementsIn(SlashCommand.entries)
    }

    @Test
    fun `typing a command runs its action rather than sending it to the model`() {
        SlashCommand.entries.forEach { command ->
            val fired = type(command.trigger)
            assertWithMessage(command.trigger).that(fired?.action)
                .isEqualTo(expected.getValue(command))
        }
    }

    @Test
    fun `a command typed the way a keyboard produces it still runs`() {
        // Autocapitalisation and a trailing space are what a phone actually sends.
        SlashCommand.entries.forEach { command ->
            val typed = " ${command.trigger.uppercase()} "
            assertWithMessage(typed).that(type(typed)?.action)
                .isEqualTo(expected.getValue(command))
        }
    }

    @Test
    fun `the two commands that take a task receive it whole`() {
        val task = "find out what changed in Android 16 and write it up"

        SlashCommand.entries.filter { it.takesArgument }.forEach { command ->
            val fired = type("${command.trigger} $task")
            assertWithMessage(command.trigger).that(fired?.action)
                .isEqualTo(expected.getValue(command))
            // The whole task, not its first word, and not with the trigger still on it.
            assertWithMessage(command.trigger).that(fired?.argument).isEqualTo(task)
        }
    }

    @Test
    fun `a command that takes no task ignores anything after it rather than running`() {
        // "/plan the trip" is a sentence about planning a trip, not a mode change. It has
        // to reach the model, which is what [submit] returning through onSend means.
        SlashCommand.entries.filterNot { it.takesArgument }.forEach { command ->
            var sent: String? = null
            val typed = "${command.trigger} the trip"
            submit(
                typed = typed,
                editingId = null,
                onDispatch = { _, _ -> error("$typed ran as a command") },
                onEdit = { _, _ -> error("nothing is being edited") },
                onSend = {
                    sent = it
                    true
                },
            )
            assertWithMessage(typed).that(sent).isEqualTo(typed)
        }
    }

    @Test
    fun `every command runs exactly one action, and no other`() {
        SlashCommand.entries.forEach { command ->
            val fired = mutableListOf<String>()
            command.run(
                onNewChat = { fired += "new" },
                onCompact = { fired += "compact" },
                onRegenerate = { fired += "retry" },
                onMode = { fired += "mode:${it.name}" },
                onGoal = { fired += "goal" },
                onResearch = { fired += "research" },
                argument = "",
            )
            assertWithMessage(command.trigger).that(fired)
                .containsExactly(expected.getValue(command))
        }
    }
}

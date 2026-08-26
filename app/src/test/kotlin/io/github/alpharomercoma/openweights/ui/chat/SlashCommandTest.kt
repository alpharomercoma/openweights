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

class SlashCommandTest {
    @Test
    fun `a bare slash offers everything`() {
        assertThat(SlashCommand.match("/")).containsExactlyElementsIn(SlashCommand.entries)
    }

    @Test
    fun `typing filters the list`() {
        assertThat(SlashCommand.match("/comp")).containsExactly(SlashCommand.COMPACT)
    }

    @Test
    fun `ordinary messages are not commands`() {
        assertThat(SlashCommand.match("what is a KV cache?")).isNull()
    }

    @Test
    fun `a message that merely starts with a slash is not a command`() {
        // "/tmp is full" is a sentence, not a command; taking over the composer here
        // would be worse than not having commands at all.
        assertThat(SlashCommand.match("/tmp is full")).isNull()
    }

    @Test
    fun `an unknown command matches nothing rather than everything`() {
        assertThat(SlashCommand.match("/zzz")).isEmpty()
    }

    @Test
    fun `a command that was typed rather than tapped is still a command`() {
        // The palette is a way of finding these, not the only way of running them. Typing
        // the whole word and pressing send is what anyone who already knows the command
        // does, and it used to send the literal text "/plan" to the model, which answered
        // it as a question.
        assertThat(SlashCommand.typed("/plan")).isEqualTo(SlashCommand.PLAN)
        assertThat(SlashCommand.typed("  /compact  ")).isEqualTo(SlashCommand.COMPACT)
        assertThat(SlashCommand.typed("/PLAN")).isEqualTo(SlashCommand.PLAN)
    }

    @Test
    fun `only an exact command counts as one`() {
        // The prefix matching that fills the palette must not reach this: half a command is
        // something the user is still typing, and a sentence beginning with one is a
        // sentence. Both would otherwise become an action nobody asked for.
        assertThat(SlashCommand.typed("/pl")).isNull()
        assertThat(SlashCommand.typed("/plan the migration")).isNull()
        assertThat(SlashCommand.typed("/tmp is full")).isNull()
        assertThat(SlashCommand.typed("what is a KV cache?")).isNull()
    }

    @Test
    fun `every mode command sets the mode it names`() {
        // The palette is the only way into a mode, so a command wired to the wrong one would
        // be a mode nobody could reach and a mode nobody could leave.
        val chosen = mutableListOf<AgentMode>()
        val modes = listOf(
            SlashCommand.PLAN to AgentMode.PLAN,
            SlashCommand.AUTO to AgentMode.AUTO,
            SlashCommand.ASK to AgentMode.ASK,
            SlashCommand.YOLO to AgentMode.YOLO,
        )

        val nothing = {}
        modes.forEach { (command, _) ->
            command.run(
                onNewChat = nothing,
                onCompact = nothing,
                onRegenerate = nothing,
                onMode = { chosen += it },
            )
        }

        assertThat(chosen).containsExactlyElementsIn(modes.map { it.second }).inOrder()
    }

    @Test
    fun `yolo says what it waives rather than reading as a faster auto`() {
        // The one command that removes a check has to be legible in a list where every other
        // line is about convenience. If this ever reads like "auto but quicker", the user
        // who taps it has not been told what they turned off.
        assertThat(SlashCommand.YOLO.description).ignoringCase().contains("everything")
        assertThat(SlashCommand.YOLO.description).ignoringCase().contains("files")
    }

    @Test
    fun `parse recognises a valid command with its argument`() {
        assertThat(SlashCommand.parse("/deep-research what changed in Android 16"))
            .isEqualTo(
                CommandParseResult.Valid(
                    SlashCommand.DEEP_RESEARCH,
                    "what changed in Android 16",
                ),
            )
        assertThat(SlashCommand.parse("/plan"))
            .isEqualTo(CommandParseResult.Valid(SlashCommand.PLAN, ""))
    }

    @Test
    fun `parse leaves an ordinary message alone`() {
        assertThat(SlashCommand.parse("what is a KV cache?"))
            .isEqualTo(CommandParseResult.OrdinaryMessage)
        assertThat(SlashCommand.parse("/tmp is full"))
            .isInstanceOf(CommandParseResult.Unknown::class.java)
    }

    @Test
    fun `parse catches the exact failure that motivated it`() {
        // A space where the trigger has a hyphen used to answer as prose with nothing on
        // screen to say a command had even been attempted.
        val result = SlashCommand.parse("/deep research what changed")
        assertThat(result).isInstanceOf(CommandParseResult.Unknown::class.java)
        val unknown = result as CommandParseResult.Unknown
        assertThat(unknown.token).isEqualTo("/deep")
        assertThat(unknown.suggestions).containsExactly(SlashCommand.DEEP_RESEARCH)
    }

    @Test
    fun `parse suggests nothing for a slash that resembles no command`() {
        val result = SlashCommand.parse("/zzz do a thing")
        assertThat(result).isInstanceOf(CommandParseResult.Unknown::class.java)
        assertThat((result as CommandParseResult.Unknown).suggestions).isEmpty()
    }

    @Test
    fun `the argument after a near miss drops the whole trigger, not just its first word`() {
        // The bug this replaced: removing only the token before the first space left
        // "research what changed" behind, so accepting the correction asked the model
        // "/deep-research research what changed" instead of the question that was typed.
        assertThat(SlashCommand.DEEP_RESEARCH.argumentAfterNearMiss("/deep research what changed"))
            .isEqualTo("what changed")
    }

    @Test
    fun `a near miss with the trigger spelled correctly still yields the right argument`() {
        assertThat(SlashCommand.DEEP_RESEARCH.argumentAfterNearMiss("/deep-research what changed"))
            .isEqualTo("what changed")
    }

    @Test
    fun `a near miss with nothing after the trigger yields an empty argument`() {
        assertThat(SlashCommand.PLAN.argumentAfterNearMiss("/pl an")).isEqualTo("")
    }

    @Test
    fun `an unrecognised command falling through as a message is a deliberate default`() {
        // Documented here because it is the one behaviour a reviewer would otherwise read
        // as a bug: OrdinaryMessage is the answer for a sentence that happens to start with
        // a slash, and Unknown is the answer for one that does not — the composer decides
        // what to do with each, this only tells them apart.
        assertThat(
            SlashCommand.parse("/tmp is full"),
        ).isNotEqualTo(CommandParseResult.OrdinaryMessage)
    }

    @Test
    fun `the mode the app starts in is the one the palette calls the default`() {
        // /ask described itself as the default while the app started in auto, so the list
        // that is meant to be the documentation told the user tools would ask first when
        // they were about to run on their own. Two places said it and only one was right.
        val started = ChatUiState().mode
        val describedAsDefault = SlashCommand.entries.filter {
            it.description.contains("default", ignoreCase = true)
        }

        assertThat(describedAsDefault.map { it.trigger }).containsExactly("/${started.command}")
    }
}

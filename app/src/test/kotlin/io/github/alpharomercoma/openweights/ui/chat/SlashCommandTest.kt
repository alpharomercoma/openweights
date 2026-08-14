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

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

/**
 * What Send does with the text in the box, outside of Compose.
 *
 * A command is checked before an ordinary send, and editing used to be checked after that:
 * reopening a past message that happened to be the literal text "/plan" for editing ran it as
 * a command on the way back out instead of resending the edit. Editing wins now, because
 * resending has to mean resending whatever the turn actually was.
 */
class SubmitTest {
    @Test
    fun `editing a message that looks like a command resends it rather than running it`() {
        var edited: Pair<Long, String>? = null
        var dispatched: SlashCommand? = null

        val handled = submit(
            typed = "/plan",
            editingId = 7L,
            onDispatch = { command, _ -> dispatched = command },
            onEdit = { id, text -> edited = id to text },
            onSend = { false },
        )

        assertThat(handled).isTrue()
        assertThat(edited).isEqualTo(7L to "/plan")
        assertThat(dispatched).isNull()
    }

    @Test
    fun `a command typed with nothing being edited still runs`() {
        var dispatched: Pair<SlashCommand, String>? = null

        val handled = submit(
            typed = "/deep-research what changed",
            editingId = null,
            onDispatch = { command, argument -> dispatched = command to argument },
            onEdit = { _, _ -> },
            onSend = { false },
        )

        assertThat(handled).isTrue()
        assertThat(dispatched).isEqualTo(SlashCommand.DEEP_RESEARCH to "what changed")
    }

    @Test
    fun `an ordinary message with nothing being edited is sent`() {
        var sent: String? = null

        val handled = submit(
            typed = "what is a KV cache?",
            editingId = null,
            onDispatch = { _, _ -> },
            onEdit = { _, _ -> },
            onSend = { text ->
                sent = text
                true
            },
        )

        assertThat(handled).isTrue()
        assertThat(sent).isEqualTo("what is a KV cache?")
    }
}

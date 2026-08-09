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
 * The marker contract with the projector.
 *
 * One marker per attachment, in the order the attachments appear: the projector matches
 * them positionally and refuses the whole prompt if the counts differ, so an off-by-one
 * here is the difference between a working reply and a hard failure.
 */
class ChatMessageTest {

    @Test
    fun `each attachment contributes exactly one marker`() {
        val message = ChatMessage(
            role = ChatRole.USER,
            parts = listOf(
                MessagePart.File("/a.jpg", "image/jpeg"),
                MessagePart.File("/b.jpg", "image/jpeg"),
                MessagePart.Text("Compare these"),
            ),
        )

        val rendered = message.withMediaMarkers(MARKER)

        assertThat(rendered.windowed(MARKER.length).count { it == MARKER }).isEqualTo(2)
        assertThat(message.files).hasSize(2)
    }

    @Test
    fun `markers keep the position the attachment had in the message`() {
        val message = ChatMessage(
            role = ChatRole.USER,
            parts = listOf(
                MessagePart.Text("Before"),
                MessagePart.File("/a.jpg", "image/jpeg"),
                MessagePart.Text("After"),
            ),
        )

        val rendered = message.withMediaMarkers(MARKER)

        assertThat(rendered.indexOf("Before")).isLessThan(rendered.indexOf(MARKER))
        assertThat(rendered.indexOf(MARKER)).isLessThan(rendered.indexOf("After"))
    }

    @Test
    fun `a message with no attachments renders as its text`() {
        val message = ChatMessage.text(ChatRole.USER, "Just words")

        assertThat(message.withMediaMarkers(MARKER)).isEqualTo("Just words")
        assertThat(message.files).isEmpty()
    }

    @Test
    fun `text omits attachments so the transcript never shows a marker`() {
        val message = ChatMessage(
            role = ChatRole.USER,
            parts = listOf(MessagePart.File("/a.jpg", "image/jpeg"), MessagePart.Text("Hello")),
        )

        assertThat(message.text).isEqualTo("Hello")
    }

    @Test
    fun `media kind comes from the type, not the file name`() {
        assertThat(MediaKind.of("image/png")).isEqualTo(MediaKind.IMAGE)
        assertThat(MediaKind.of("AUDIO/WAV")).isEqualTo(MediaKind.AUDIO)
        assertThat(MediaKind.of("video/mp4")).isEqualTo(MediaKind.VIDEO)
        assertThat(MediaKind.of("application/pdf")).isEqualTo(MediaKind.OTHER)
        assertThat(MediaKind.of("nonsense")).isEqualTo(MediaKind.OTHER)
    }

    private companion object {
        /** libmtmd's own default, and what the engine passes through at runtime. */
        const val MARKER = "<__media__>"
    }
}

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

package io.github.alpharomercoma.openweights.core.data

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import org.junit.Test

class StoredAttachmentsTest {

    @Test
    fun `attachments survive a round trip`() {
        val attachments = listOf(
            MessagePart.File("/data/a.jpg", "image/jpeg", "holiday.jpg"),
            MessagePart.File("/data/b.wav", "audio/wav"),
        )

        val restored = attachments.encodeAttachments().decodeAttachments()

        assertThat(restored).isEqualTo(attachments)
    }

    @Test
    fun `no attachments stores nothing`() {
        assertThat(emptyList<MessagePart.File>().encodeAttachments()).isNull()
        assertThat(null.decodeAttachments()).isEmpty()
        assertThat("".decodeAttachments()).isEmpty()
    }

    @Test
    fun `an unreadable value costs the thumbnails, not the conversation`() {
        assertThat("{not json".decodeAttachments()).isEmpty()
    }

    @Test
    fun `a field added by a later version is ignored rather than fatal`() {
        val forwards = """[{"path":"/a.jpg","mediaType":"image/jpeg","durationMs":1200}]"""

        val restored = forwards.decodeAttachments()

        assertThat(restored).hasSize(1)
        assertThat(restored.single().path).isEqualTo("/a.jpg")
    }
}

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

package io.github.alpharomercoma.openweights.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedInputStreamTest {
    @Test
    fun `skip counts toward the provider byte limit`() {
        val stream = ByteArrayInputStream(ByteArray(16)).bounded(limit = 8)

        assertEquals(6L, stream.skip(6))
        assertEquals(2, stream.read(ByteArray(2)))
        assertThrows(AttachmentTooLargeException::class.java) { stream.skip(1) }
    }
}

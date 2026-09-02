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

package io.github.alpharomercoma.openweights.core.hub

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The cursor a search hands out names the runtime whose next page it reaches. */
class PagingCursorTest {
    @Test
    fun `a cursor comes back with the runtime it was tagged with`() {
        val encoded = PagingCursor(HubRuntime.EXECUTORCH, "abc|def").encode()

        // The Hub's own cursor may hold the separator; only the first one is the tag.
        assertThat(PagingCursor.decode(encoded))
            .isEqualTo(PagingCursor(HubRuntime.EXECUTORCH, "abc|def"))
    }

    @Test
    fun `a cursor with no tag is read as llama cpp's, which every cursor once was`() {
        assertThat(PagingCursor.decode("opaque"))
            .isEqualTo(PagingCursor(HubRuntime.LLAMA_CPP, "opaque"))
    }
}

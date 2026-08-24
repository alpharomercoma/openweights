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

package io.github.alpharomercoma.openweights.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The half of the renderer that needs no canvas.
 *
 * `PdfDocument` draws through Skia and Robolectric has no working shadow for it: every page
 * reports the document closed, with or without native graphics mode. So the layout is proved
 * on a device in `MarkdownPdfOnDeviceTest` and the text transformation is proved here, where
 * it runs in milliseconds.
 */
class StripInlineTest {
    @Test
    fun `emphasis is removed rather than printed`() {
        // One font, no way to bold a span. "the **important** part" on paper is worse than
        // "the important part".
        assertThat("the **important** part".stripInline()).isEqualTo("the important part")
        assertThat("some *stress* here".stripInline()).isEqualTo("some stress here")
        assertThat("a `symbol` here".stripInline()).isEqualTo("a symbol here")
    }

    @Test
    fun `a link keeps its address, since nobody can click a printed one`() {
        assertThat("see [the docs](https://example.com) for more".stripInline())
            .isEqualTo("see the docs (https://example.com) for more")
    }

    @Test
    fun `text with no markup is untouched`() {
        val plain = "A sentence with a * star and an underscore_ in it."
        assertThat(plain.stripInline()).isEqualTo(plain)
    }
}

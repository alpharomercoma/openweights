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

package io.github.alpharomercoma.openweights.ui.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SlidesTest {
    @Test
    fun `separator lines split the deck`() {
        val deck = "# Title\n\n---\n\n## Second\npoint\n\n---\n\n## Third"

        val slides = deck.asSlides()

        assertThat(slides).hasSize(3)
        assertThat(slides[0]).isEqualTo("# Title")
        assertThat(slides[2]).isEqualTo("## Third")
    }

    @Test
    fun `a file with no separators is one slide, not none`() {
        assertThat("just some notes".asSlides()).containsExactly("just some notes")
    }

    @Test
    fun `an opening separator or front matter fence leaves no blank title slide`() {
        val deck = "---\ntitle: Talk\n---\n\n# Real title\n\n---\n\n## Next"

        val slides = deck.asSlides()

        // The front-matter body survives as a block - it is content the model wrote -
        // but nothing renders as an empty page.
        assertThat(slides).doesNotContain("")
        assertThat(slides.last()).isEqualTo("## Next")
    }

    @Test
    fun `dashes inside a line do not split`() {
        val deck = "# One\nranges like 3---5 stay\n\n---\n\n# Two"

        assertThat(deck.asSlides()).hasSize(2)
    }
}

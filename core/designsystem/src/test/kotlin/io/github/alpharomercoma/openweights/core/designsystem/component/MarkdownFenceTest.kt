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

package io.github.alpharomercoma.openweights.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The half-written code block, which is what a code block is for most of its life.
 *
 * A fence with no end makes the parser hold the line it is still reading, so a reply being
 * streamed showed its code one line behind the model and only caught up when the closing
 * fence arrived. Measured on a device: four lines written, three drawn.
 */
class MarkdownFenceTest {
    @Test
    fun `a fence still being written is closed`() {
        val open = "```kotlin\nval x = 1"

        assertThat(open.withClosedFence()).isEqualTo("```kotlin\nval x = 1\n```")
    }

    @Test
    fun `a finished block is left exactly as it is`() {
        val done = "```kotlin\nval x = 1\n```"

        assertThat(done.withClosedFence()).isEqualTo(done)
    }

    @Test
    fun `two finished blocks are still finished`() {
        val two = "```\na\n```\ntext\n```\nb\n```"

        assertThat(two.withClosedFence()).isEqualTo(two)
    }

    @Test
    fun `an indented fence counts, because a fence inside a list is still a fence`() {
        val nested = "- step\n  ```bash\n  ls"

        assertThat(nested.withClosedFence()).endsWith("\n```")
    }

    @Test
    fun `prose with no fence at all is untouched`() {
        val prose = "Just a sentence with `inline code` in it."

        assertThat(prose.withClosedFence()).isEqualTo(prose)
    }

    @Test
    fun `a picture becomes the link it came from`() {
        // Nothing here can draw a remote image, and the renderer's empty square said
        // nothing at all. The alt text is the description; the address is the only way to
        // go and look.
        val md = "Before ![a chart](https://example.com/c.png) after."

        assertThat(md.withLinkedImages())
            .isEqualTo("Before [a chart](https://example.com/c.png) after.")
    }

    @Test
    fun `a picture with no description falls back to its address`() {
        val md = "![](https://example.com/c.png)"

        assertThat(
            md.withLinkedImages(),
        ).isEqualTo("[https://example.com/c.png](https://example.com/c.png)")
    }

    @Test
    fun `a title on the image is dropped rather than left dangling`() {
        val md = """![alt](https://example.com/c.png "a title")"""

        assertThat(md.withLinkedImages()).isEqualTo("[alt](https://example.com/c.png)")
    }

    @Test
    fun `an ordinary link is not touched`() {
        val md = "A [link](https://example.com) stays a link."

        assertThat(md.withLinkedImages()).isEqualTo(md)
    }

    @Test
    fun `image syntax inside a code block is left exactly as written`() {
        // A code block showing somebody how to write an image is doing it on purpose.
        val md = "```markdown\n![alt](https://example.com/c.png)\n```"

        assertThat(md.withLinkedImages()).isEqualTo(md)
    }

    @Test
    fun `a task list gets the box the renderer drops`() {
        val md = "- [ ] not done\n- [x] done"

        assertThat(md.withCheckboxes()).isEqualTo("- \u2610 not done\n- \u2611 done")
    }

    @Test
    fun `a capital X ticks it too, and indentation survives`() {
        val md = "  * [X] nested and done"

        assertThat(md.withCheckboxes()).isEqualTo("  * \u2611 nested and done")
    }

    @Test
    fun `an ordinary bullet is not mistaken for a task`() {
        val md = "- just a bullet\n- [a link](https://example.com) in one"

        assertThat(md.withCheckboxes()).isEqualTo(md)
    }

    @Test
    fun `a checkbox inside a code block is left exactly as written`() {
        val md = "```\n- [ ] literal\n```"

        assertThat(md.withCheckboxes()).isEqualTo(md)
    }
}

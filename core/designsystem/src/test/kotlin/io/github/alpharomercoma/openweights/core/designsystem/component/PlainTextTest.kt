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
 * What "copy text" hands over, now that "copy as Markdown" hands over the source.
 *
 * Every case here is a way the two differ. The cases worth guarding are not the easy ones
 * — the ones where taking a mark off would take something the reader needs with it.
 */
class PlainTextTest {
    @Test
    fun `emphasis and headings lose their marks`() {
        val source = "## Findings\n\nIt is **fast** and *cheap* and ~~slow~~."

        assertThat(source.markdownToPlainText())
            .isEqualTo("Findings\n\nIt is fast and cheap and slow.")
    }

    @Test
    fun `a bullet stays a bullet, because a list without one is not a list`() {
        val source = "- one\n- two\n  - nested"

        assertThat(source.markdownToPlainText()).isEqualTo("• one\n• two\n  • nested")
    }

    @Test
    fun `a numbered list keeps its numbers`() {
        assertThat("1. one\n2. two".markdownToPlainText()).isEqualTo("1. one\n2. two")
    }

    @Test
    fun `a task list keeps a box that can be read`() {
        assertThat("- [x] done\n- [ ] not".markdownToPlainText())
            .isEqualTo("• ☑ done\n• ☐ not")
    }

    @Test
    fun `code inside a fence is left exactly as written`() {
        val source = "Try this:\n\n```kotlin\nval x = a * b * c\n```\n\nDone."

        assertThat(source.markdownToPlainText())
            .isEqualTo("Try this:\n\nval x = a * b * c\n\nDone.")
    }

    @Test
    fun `an inline code span keeps the marks that are part of what it shows`() {
        val source = "Pass `**kwargs` to it, and `_private` stays."

        assertThat(source.markdownToPlainText())
            .isEqualTo("Pass **kwargs to it, and _private stays.")
    }

    @Test
    fun `a link keeps its address, which the label alone would lose`() {
        val source = "See [the paper](https://arxiv.org/abs/1)."

        assertThat(source.markdownToPlainText())
            .isEqualTo("See the paper (https://arxiv.org/abs/1).")
    }

    @Test
    fun `a link whose label is already the address is not written twice`() {
        val source = "[https://example.com](https://example.com)"

        assertThat(source.markdownToPlainText()).isEqualTo("https://example.com")
    }

    @Test
    fun `an image becomes the same thing the renderer turns it into`() {
        val source = "![a chart](https://example.com/c.png)"

        assertThat(source.markdownToPlainText())
            .isEqualTo("a chart (https://example.com/c.png)")
    }

    @Test
    fun `an autolink comes out as the bare address`() {
        assertThat("Mail <mailto:a@b.com> now.".markdownToPlainText())
            .isEqualTo("Mail mailto:a@b.com now.")
    }

    @Test
    fun `snake case survives, which a naive underscore rule would eat`() {
        val source = "Call some_long_name and __dunder__ is bold."

        assertThat(source.markdownToPlainText())
            .isEqualTo("Call some_long_name and dunder is bold.")
    }

    @Test
    fun `a lone asterisk is not an emphasis mark`() {
        assertThat("2 * 3 * 4 = 24".markdownToPlainText()).isEqualTo("2 * 3 * 4 = 24")
    }

    @Test
    fun `a quote keeps its words and drops its bar`() {
        assertThat("> quoted\n> more".markdownToPlainText()).isEqualTo("quoted\nmore")
    }

    @Test
    fun `a table is left alone, because pipes are how plain text draws one`() {
        val source = "| a | b |\n| --- | --- |\n| 1 | 2 |"

        assertThat(source.markdownToPlainText()).isEqualTo(source)
    }

    @Test
    fun `a rule is not mistaken for a bullet`() {
        assertThat("one\n\n---\n\ntwo".markdownToPlainText()).isEqualTo("one\n\n---\n\ntwo")
    }

    @Test
    fun `plain prose is returned unchanged`() {
        val source = "Nothing here is Markdown at all."

        assertThat(source.markdownToPlainText()).isEqualTo(source)
    }
}

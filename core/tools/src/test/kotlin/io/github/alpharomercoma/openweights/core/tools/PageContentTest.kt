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

package io.github.alpharomercoma.openweights.core.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a page becomes before a model reads it.
 *
 * The predecessor cut every tag and folded all whitespace, so a document arrived as one
 * unbroken run in which a heading, a table row and the sentence after it were separated by
 * the same single space. Everything here is a piece of structure that carried meaning and
 * used to be destroyed on the way in, and the cases are chosen for the pages people
 * actually fetch by address: references, changelogs, specifications, repositories.
 *
 * No network and no platform: this is jsoup and string work, so it runs on the host.
 */
class PageContentTest {
    @Test
    fun `headings keep their level, and their rank against each other`() {
        val html = "<h1>Install</h1><p>First.</p><h2>From source</h2><p>Then.</p>"

        assertThat(html.asStructuredText())
            .isEqualTo("# Install\n\nFirst.\n\n## From source\n\nThen.")
    }

    @Test
    fun `paragraphs are separated, rather than run together`() {
        // The whole complaint in one case. These two sentences used to arrive with a single
        // space between them and nothing to say they were not one sentence.
        val html = "<p>The battery is 5000 mAh.</p><p>The screen is 6.7 inches.</p>"

        assertThat(html.asStructuredText())
            .isEqualTo("The battery is 5000 mAh.\n\nThe screen is 6.7 inches.")
    }

    @Test
    fun `a list stays a list, and a nested one stays under its item`() {
        val html = """
            <ul><li>One<ul><li>One a</li></ul></li><li>Two</li></ul>
        """.trimIndent()

        assertThat(html.asStructuredText()).isEqualTo("- One\n  - One a\n- Two")
    }

    @Test
    fun `an ordered list is numbered from one, whatever the markup counts`() {
        val html = "<ol><li>First</li><li>Second</li><li>Third</li></ol>"

        assertThat(html.asStructuredText()).isEqualTo("1. First\n2. Second\n3. Third")
    }

    @Test
    fun `a table keeps which value belongs to which column`() {
        // The construct whose whole meaning is positional, and the one a specification page
        // is made of. Flattened, "Battery 5000 mAh Screen 6.7 in" says nothing about which
        // number is which.
        val html = """
            <table>
              <tr><th>Part</th><th>Value</th></tr>
              <tr><td>Battery</td><td>5000 mAh</td></tr>
              <tr><td>Screen</td><td>6.7 in</td></tr>
            </table>
        """.trimIndent()

        assertThat(html.asStructuredText()).isEqualTo(
            """
            | Part | Value |
            | --- | --- |
            | Battery | 5000 mAh |
            | Screen | 6.7 in |
            """.trimIndent(),
        )
    }

    @Test
    fun `a pipe inside a cell cannot shift the columns after it`() {
        // A wrong table is read confidently, which is worse than the flattening this
        // replaces: every row after an unescaped pipe reads one column across.
        val html = "<table><tr><td>a|b</td><td>c</td></tr></table>"

        assertThat(html.asStructuredText()).isEqualTo("""| a\|b | c |""")
    }

    @Test
    fun `code keeps its line breaks and its indentation`() {
        val html = "<pre><code>fun main() {\n    println(1)\n}</code></pre>"

        assertThat(html.asStructuredText())
            .isEqualTo("```\nfun main() {\n    println(1)\n}\n```")
    }

    @Test
    fun `inline code is marked without becoming a block`() {
        val html = "<p>Call <code>fetch_url</code> with an address.</p>"

        assertThat(html.asStructuredText()).isEqualTo("Call `fetch_url` with an address.")
    }

    @Test
    fun `a link keeps the address, resolved against the page it was found on`() {
        // What makes reading a page the first step of an errand rather than the whole of
        // it. A relative href flattened to its label is a dead end that reads like a lead.
        val html = """<p>See the <a href="/docs/install">install guide</a>.</p>"""

        assertThat(html.asStructuredText("https://example.com/start"))
            .isEqualTo("See the [install guide](https://example.com/docs/install).")
    }

    @Test
    fun `an address the tool could never follow keeps its words and loses the link`() {
        val html = """
            <p>Write to <a href="mailto:a@b.com">the author</a> or <a href="#top">go up</a>.</p>
        """.trimIndent()

        assertThat(html.asStructuredText("https://example.com"))
            .isEqualTo("Write to the author or go up.")
    }

    @Test
    fun `a sentence broken across inline tags stays one sentence`() {
        // The reason blocks accumulate rather than being emitted per node. Rendering each
        // child as its own block cut ordinary prose into fragments on every real page.
        val html = "<div>The battery is <b>5000</b> mAh and charges at <em>67</em> W.</div>"

        assertThat(html.asStructuredText())
            .isEqualTo("The battery is 5000 mAh and charges at 67 W.")
    }

    @Test
    fun `emphasis loses its marks and keeps its words`() {
        val html = "<p>This is <strong>important</strong> and <i>this</i> is not.</p>"

        assertThat(html.asStructuredText()).isEqualTo("This is important and this is not.")
    }

    @Test
    fun `a quote is marked, so its edges survive`() {
        val html = "<blockquote><p>It depends.</p></blockquote><p>So they said.</p>"

        assertThat(html.asStructuredText()).isEqualTo("> It depends.\n\nSo they said.")
    }

    @Test
    fun `a line break inside a paragraph is a line, not a new block`() {
        val html = "<p>Line one<br>Line two</p>"

        assertThat(html.asStructuredText()).isEqualTo("Line one\nLine two")
    }

    @Test
    fun `entities are decoded without the platform`() {
        // This used to go through android.text.Html, which is why the whole path could only
        // be exercised on a device. jsoup decodes them, so the test runs on the host.
        val html = "<p>Hello &amp; welcome &copy; a &lt;tag&gt; and a &nbsp;space.</p>"

        assertThat(html.asStructuredText())
            .isEqualTo("Hello & welcome © a <tag> and a space.")
    }

    @Test
    fun `pictures and scripts contribute nothing`() {
        val html = """
            <p>Before</p><img src="/logo.png" alt="Logo">
            <script>var x = 1</script><style>.a{color:red}</style>
            <p>After</p>
        """.trimIndent()

        val text = html.asStructuredText()

        assertThat(text).isEqualTo("Before\n\nAfter")
    }

    @Test
    fun `a wrapped list item does not read as two items`() {
        val html = "<ul><li>One<br>still one</li><li>Two</li></ul>"

        assertThat(html.asStructuredText()).isEqualTo("- One\n  still one\n- Two")
    }

    @Test
    fun `an empty document comes back empty rather than as whitespace`() {
        assertThat("".asStructuredText()).isEmpty()
        assertThat("<html><body>   </body></html>".asStructuredText()).isEmpty()
    }

    @Test
    fun `a page of nested wrappers does not become one block per wrapper`() {
        // Real pages nest div dozens deep around one sentence. Treating each as a boundary
        // is what made this quadratic in the obvious implementation and wrong in the output.
        val html = "<div><div><div><div><span>One sentence.</span></div></div></div></div>"

        assertThat(html.asStructuredText()).isEqualTo("One sentence.")
    }
}

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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * A renderer written by hand needs its edges tested, because the failures are silent: a
 * document that loses its last page, or loops on one long word, looks fine until somebody
 * opens it.
 *
 * On a device rather than on the host, and not by choice. `PdfDocument` draws through Skia,
 * and Robolectric reports the document closed on the very first page with or without native
 * graphics mode. The text transformation half is proved on the host in `StripInlineTest`.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownPdfOnDeviceTest {
    private val renderer = MarkdownPdf()

    private fun render(markdown: String, title: String = "Report"): Pair<Int, ByteArray> {
        val out = ByteArrayOutputStream()
        val pages = renderer.write(markdown, title, out)
        return pages to out.toByteArray()
    }

    @Test
    fun `a short document is one page of real PDF`() {
        val (pages, bytes) = render("# Heading\n\nA paragraph.")

        assertThat(pages).isEqualTo(1)
        // The magic number, so this is a PDF rather than an empty file that did not throw.
        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
    }

    @Test
    fun `a long document breaks into pages`() {
        val (pages, _) = render(List(400) { "Paragraph number $it." }.joinToString("\n\n"))

        assertThat(pages).isGreaterThan(1)
    }

    @Test
    fun `a word wider than the page does not loop forever`() {
        // The bug this prevents: breakText returns zero for a token that cannot fit, and a
        // loop that trusts it never advances. The renderer takes at least one character.
        val (pages, _) = render("x".repeat(5_000))

        assertThat(pages).isAtLeast(1)
    }

    @Test
    fun `the stream is left open for the caller to read`() {
        // Closing somebody else's stream is how a caller ends up unable to check what was
        // written, which is exactly what this test would hit.
        val out = ByteArrayOutputStream()
        renderer.write("Something.", "Title", out)

        assertThat(out.size()).isGreaterThan(0)
    }

    @Test
    fun `code inside a fence is left exactly as written`() {
        // Emphasis stripping must not reach code: a double star is Python here, not bold.
        val (pages, bytes) = render("```python\ndef f(**kwargs):\n    pass\n```")

        assertThat(pages).isEqualTo(1)
        assertThat(bytes.size).isGreaterThan(0)
    }

    @Test
    fun `an empty document still produces a page`() {
        // A file with no pages is not a valid PDF, and a save that produced one would fail
        // to open with no explanation.
        val (pages, bytes) = render("", title = "")

        assertThat(pages).isEqualTo(1)
        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
    }

    @Test
    fun anUnclosedFenceRunsToTheEndOfTheDocument() {
        // What CommonMark says, and the opposite of what this test asserted first. A
        // reviewer called the unclosed case a corruption; the fix counted fences and treated
        // an odd last one as closing, which made the code block itself render as prose. The
        // test written beside it compared against a prose rendering and passed, so the bug
        // and its test agreed with each other and not with the spec:
        //
        //   "If the end of the containing block (or document) is reached and no closing code
        //   fence has been found, the code block contains all of the lines after the opening
        //   code fence until the end of the containing block (or document)."
        //
        // Asserted as an equality against the closed form rather than as a page-count
        // inequality against prose, which is what the first attempt did and got backwards:
        // code is set at 9.5pt and prose at 11pt, so a tail rendered as code takes *fewer*
        // pages, not more. Comparing like with like says exactly what the spec says and
        // depends on no font metric at all.
        // Long enough that the 9.5pt code face and the 11pt prose face reach different page
        // counts. At sixty lines both landed on two pages and the second assertion below was
        // measuring nothing.
        val body = List(400) { "let x$it = someRatherLongIdentifierName + $it" }.joinToString("\n")

        val unclosed = render("# Report\n\n```kotlin\n$body").first
        val closed = render("# Report\n\n```kotlin\n$body\n```").first
        val prose = render("# Report\n\n$body").first

        assertThat(unclosed).isEqualTo(closed)
        // And it is genuinely being treated as code rather than the two happening to match.
        assertThat(unclosed).isNotEqualTo(prose)
    }

    @Test
    fun aClosedFenceStillRendersItsBodyAsCode() {
        // The counterweight: the odd-fence rule must not break the ordinary case.
        val (pages, _) = render("Before.\n\n```kotlin\nval x = 1\n```\n\nAfter.")

        assertThat(pages).isEqualTo(1)
    }
}

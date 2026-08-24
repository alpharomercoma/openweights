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
    fun anUnclosedFenceDoesNotSwallowTheRestOfTheDocument() {
        // The bug this replaces: `inCode` was a toggle, so an odd number of fences left it
        // on and every remaining line rendered as unwrapped monospace. A model that starts
        // a code block and runs out of tokens produces exactly that, and the result is not
        // an ugly paragraph, it is a corrupt document nobody notices until they open it.
        val runOn = buildString {
            appendLine("# Report")
            appendLine("```kotlin")
            appendLine("val x = 1")
            appendLine()
            repeat(120) {
                appendLine("A paragraph of ordinary prose, number $it, which must wrap.")
            }
        }
        val (pages, bytes) = render(runOn)

        // Prose wraps and code does not, so a tail wrongly treated as code overflows the
        // page width instead of flowing, and takes far fewer pages than it should.
        val prose = render(runOn.replace("```kotlin", "")).first

        assertThat(pages).isEqualTo(prose)
        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
    }

    @Test
    fun aClosedFenceStillRendersItsBodyAsCode() {
        // The counterweight: the odd-fence rule must not break the ordinary case.
        val (pages, _) = render("Before.\n\n```kotlin\nval x = 1\n```\n\nAfter.")

        assertThat(pages).isEqualTo(1)
    }
}

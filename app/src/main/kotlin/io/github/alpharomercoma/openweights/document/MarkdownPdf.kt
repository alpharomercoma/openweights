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

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.OutputStream

/**
 * Markdown to a PDF, laid out here rather than by a library.
 *
 * ### Why by hand
 *
 * The alternatives are a HTML renderer or a PDF library. A WebView would mean building the
 * page, waiting for a layout pass on the main thread and printing through
 * `PrintDocumentAdapter`, which is asynchronous, needs a window and is hard to test; this
 * runs anywhere and is tested by counting pages and reading bytes. A PDF library is
 * megabytes of dependency for a document that is headings, paragraphs, lists and code.
 *
 * What that costs is worth stating plainly: this understands the Markdown a model actually
 * writes and nothing more. Headings, paragraphs, bullet and numbered lists, fenced code,
 * block quotes, rules, and inline emphasis. Not tables, not images, not clickable links. A
 * table arrives as its own source text rather than as a grid, which is ugly and readable,
 * and both of those beat dropping it.
 *
 * A4 rather than Letter, because everywhere outside North America uses it.
 */
class MarkdownPdf(
    private val pageWidth: Int = A4_WIDTH,
    private val pageHeight: Int = A4_HEIGHT,
    private val margin: Int = MARGIN,
) {
    init {
        // Checked rather than trusted, because the failure is not a bad-looking page: with
        // no usable height every line starts a new one and the document grows until memory
        // runs out. A constructor is the cheapest place to say no.
        require(pageWidth > margin * 2) { "page is narrower than its margins" }
        require(pageHeight > margin * 2 + TITLE_SIZE * LEADING) {
            "page is shorter than one line of text"
        }
    }

    /** Everything a page needs while it is being filled, so the helpers are not six-armed. */
    private class Sheet(private val owner: MarkdownPdf, val document: PdfDocument) {
        var page: PdfDocument.Page = document.startPage(owner.pageInfo(1))
        var y: Float = owner.margin.toFloat()

        val canvas: Canvas get() = page.canvas

        fun newPage() {
            document.finishPage(page)
            page = document.startPage(owner.pageInfo(document.pages.size + 1))
            y = owner.margin.toFloat()
        }

        /**
         * Breaks the page when the next line would not fit under the bottom margin.
         *
         * The `y > margin` guard is what stops a runaway. On geometry where one line is
         * taller than the usable height, the check is true on a fresh page too, so every
         * draw would start another page and none would ever hold anything: pages allocate
         * until the process dies. Refusing to break a page that has nothing on it yet means
         * such a line overflows its page, which is visibly wrong and finite.
         */
        fun room(needed: Float) {
            if (y + needed > owner.pageHeight - owner.margin && y > owner.margin.toFloat()) {
                newPage()
            }
        }
    }

    /**
     * Writes [markdown] into [out] as a PDF and returns how many pages it took.
     *
     * The stream is not closed here. Whoever opened it knows whether it is a file, a content
     * URI or a buffer in a test, and closing somebody else's stream is how a caller ends up
     * unable to read back what was written.
     */
    fun write(markdown: String, title: String, out: OutputStream): Int {
        val document = PdfDocument()
        // PdfDocument holds native memory, and a throw between here and the close below
        // leaks it for the life of the process. `use` is not available: it is not Closeable.
        return try {
            layout(document, markdown, title, out)
        } finally {
            document.close()
        }
    }

    private fun layout(
        document: PdfDocument,
        markdown: String,
        title: String,
        out: OutputStream,
    ): Int {
        val sheet = Sheet(this, document)
        val body = Paint().apply {
            textSize = BODY_SIZE
            isAntiAlias = true
        }
        val mono = Paint(body).apply {
            typeface = Typeface.MONOSPACE
            textSize = CODE_SIZE
        }

        // The title once, at the top. A document a model wrote has a subject and no cover,
        // and a PDF with neither is hard to find again on a phone.
        if (title.isNotBlank()) {
            val heading = Paint(body).apply {
                textSize = TITLE_SIZE
                typeface = Typeface.DEFAULT_BOLD
            }
            draw(sheet, title, heading)
            sheet.y += TITLE_SIZE
        }

        // A plain toggle, which is what CommonMark specifies and what a previous attempt at
        // this got wrong in both directions.
        //
        // One reviewer called the unclosed case a corruption: a model that opens a code
        // block and runs out of tokens leaves the rest of the document in monospace. The
        // fix was to count the fences and treat an odd last one as closing, which inverted
        // the common case: with a single unclosed fence the block itself then rendered as
        // prose, and the test written alongside it compared against prose and passed.
        //
        // The spec settles it. "If the end of the containing block (or document) is reached
        // and no closing code fence has been found, the code block contains all of the lines
        // after the opening code fence until the end of the containing block (or document)."
        // A truncated reply really does mean the tail is code. A renderer's job is to be
        // right, not to guess what the author meant to write.
        var inCode = false

        markdown.lines().forEach { raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith(FENCE)) {
                inCode = !inCode
                sheet.y += BODY_SIZE / 2
                return@forEach
            }
            if (inCode) {
                // Not stripped and not wrapped on words: code is what it says it is.
                draw(sheet, line.ifBlank { " " }, mono)
            } else {
                block(sheet, line, body)
            }
        }

        document.finishPage(sheet.page)
        val pages = document.pages.size
        document.writeTo(out)
        return pages
    }

    /** One line of prose, whichever kind of line it turned out to be. */
    private fun block(sheet: Sheet, line: String, body: Paint) {
        val trimmed = line.trimStart()
        when {
            trimmed.isBlank() -> sheet.y += BODY_SIZE / 2

            RULE.matches(trimmed) -> {
                sheet.room(BODY_SIZE)
                sheet.canvas.drawLine(
                    margin.toFloat(),
                    sheet.y,
                    (pageWidth - margin).toFloat(),
                    sheet.y,
                    Paint().apply { strokeWidth = 1f },
                )
                sheet.y += BODY_SIZE
            }

            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length
                val paint = Paint(body).apply {
                    textSize = headingSize(level)
                    typeface = Typeface.DEFAULT_BOLD
                }
                sheet.y += paint.textSize / 2
                draw(sheet, trimmed.dropWhile { it == '#' }.trim().stripInline(), paint)
                sheet.y += paint.textSize * HEADING_GAP_AFTER
            }

            trimmed.startsWith("> ") ->
                draw(sheet, "  ${trimmed.removePrefix("> ").stripInline()}", body)

            BULLET.matches(trimmed) ->
                draw(sheet, "  • ${trimmed.dropWhile { it != ' ' }.trim().stripInline()}", body)

            NUMBERED.matches(trimmed) -> draw(sheet, "  ${trimmed.stripInline()}", body)

            else -> draw(sheet, trimmed.stripInline(), body)
        }
    }

    /**
     * Draws text, wrapping it to the page and breaking pages as it goes.
     *
     * Wrapped by measuring rather than by counting characters, because in a proportional
     * font a character count is a guess: "IIIII" and "MMMMM" are the same count and nowhere
     * near the same width.
     */
    private fun draw(sheet: Sheet, text: String, paint: Paint) {
        val width = (pageWidth - margin * 2).toFloat()
        var rest = text
        while (rest.isNotEmpty()) {
            // At least one character, or a single word wider than the page never advances.
            val fits = paint.breakText(rest, true, width, null).coerceAtLeast(1)
            val soft = rest.lastIndexOf(' ', fits - 1)
            val cut = if (fits < rest.length && soft > 0) soft else fits
            sheet.room(paint.textSize * LEADING)
            sheet.canvas.drawText(rest.substring(0, cut), margin.toFloat(), sheet.y, paint)
            sheet.y += paint.textSize * LEADING
            rest = rest.substring(cut).trimStart()
        }
    }

    private fun pageInfo(number: Int) =
        PdfDocument.PageInfo.Builder(pageWidth, pageHeight, number).create()

    private fun headingSize(level: Int): Float = when (level) {
        1 -> TITLE_SIZE
        2 -> H2_SIZE
        else -> H3_SIZE
    }

    companion object {
        const val A4_WIDTH = 595
        const val A4_HEIGHT = 842

        /** About 20mm, which is what a document meant to be printed wants. */
        const val MARGIN = 56

        private const val FENCE = "```"
        private const val BODY_SIZE = 11f
        private const val CODE_SIZE = 9.5f
        private const val TITLE_SIZE = 20f
        private const val H2_SIZE = 15f
        private const val H3_SIZE = 12.5f

        /** Line height as a multiple of the size. */
        private const val LEADING = 1.45f

        /** Breathing room under a heading, as a fraction of its own size. */
        private const val HEADING_GAP_AFTER = 0.25f

        private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
        private val BULLET = Regex("""^[-*+]\s+.*""")
        private val NUMBERED = Regex("""^\d+[.)]\s+.*""")
    }
}

/**
 * Inline Markdown removed, because there is one font here and no way to show emphasis.
 *
 * Stripped rather than left in place: a printed page reading "the **important** part" is
 * worse than one reading "the important part", and this renderer cannot bold a span in the
 * middle of a line without measuring and drawing each run on its own.
 *
 * A link keeps its address in brackets after the text, because a PDF nobody can click is
 * still a PDF somebody can read the address out of.
 */
internal fun String.stripInline(): String = this
    .replace(Regex("""\[(.+?)]\((.+?)\)"""), "$1 ($2)")
    .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
    .replace(Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)"""), "$1")
    .replace(Regex("""`(.+?)`"""), "$1")

/** Writes to a file and returns it, for a caller that wants one on disk. */
fun MarkdownPdf.writeTo(file: File, markdown: String, title: String): File {
    file.outputStream().use { write(markdown, title, it) }
    return file
}

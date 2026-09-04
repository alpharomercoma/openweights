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

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * A page as something worth reading, rather than as a paragraph with the tags cut out.
 *
 * What this replaces removed every `<tag>` and put a space where it had been, then folded
 * all whitespace into single spaces. Everything the page's author used to say what the
 * shape of the document was went with them: a reference page came back as one unbroken
 * four-thousand-character run in which a heading, the row of a specification table and the
 * sentence after it were separated by exactly the same single space. A model reading that
 * cannot tell a column heading from its value, cannot tell where one list item ends and the
 * next begins, and answers out of whichever fragment it happened to latch onto.
 *
 * So the markup is parsed rather than deleted, and the structure it describes is rewritten
 * as the plain-text conventions that carry the same information: `#` headings, `-` and `1.`
 * lists, `|` table rows, fenced code, `>` quotes, blank lines between blocks. That is the
 * same Markdown the models are trained on and the same the app renders replies in, so it is
 * the notation they already read best.
 *
 * ### What is deliberately dropped
 *
 * Bold and italic marks, because emphasis is the one piece of structure that changes no
 * meaning a model acts on, and every mark is tokens off a four-thousand-character budget.
 * Images, because a text model cannot see one and the alt text on a real page is mostly
 * `""`, a logo, or an icon name. Anchors that go nowhere a fetch could follow: `mailto:`,
 * `javascript:` and in-page `#` fragments keep their words and lose the address.
 *
 * ### What is deliberately kept
 *
 * Links, as `[label](https://...)`, resolved against the address the page came from. This
 * is what makes reading a page the first step of an errand rather than the whole of it: the
 * tool chains, the page usually names the better page, and a link stripped of its address
 * is a dead end that reads like a live one. The furniture pass has already taken `<nav>`,
 * `<header>`, `<footer>` and `<aside>`, so what survives to here is the links a writer put
 * in the prose.
 *
 * @param baseUrl the address the page was fetched from, so relative links come out whole.
 */
internal fun String.asStructuredText(baseUrl: String = ""): String =
    Jsoup.parse(this, baseUrl).body().let { body ->
        val blocks = Blocks()
        body.childNodes().forEach { it.render(blocks) }
        blocks.finish().joinToString("\n\n").collapseBlankRuns()
    }

/**
 * The blocks built so far, and the inline run still being written.
 *
 * A document does not divide cleanly into blocks and inlines: `<div>some text <span>more
 * text</span> and the rest</div>` is one sentence in three nodes, and rendering each child
 * as its own block would cut it into three. So inline content accumulates here and only
 * becomes a block when something block-shaped arrives, which is what [flush] is. One pass,
 * no lookahead, and no asking a node whether its subtree contains a block: that question
 * costs a walk of the subtree per node, which on a page of nested `div`s is quadratic.
 */
private class Blocks {
    private val done = mutableListOf<String>()
    private val current = StringBuilder()

    fun inline(text: String) {
        current.append(text)
    }

    /** Ends the inline run, if there is one, without adding anything. */
    fun flush() {
        current.toString().collapsed().takeIf { it.isNotEmpty() }?.let { done += it }
        current.setLength(0)
    }

    /** Ends the inline run and adds [text] as a block of its own. */
    fun block(text: String) {
        flush()
        if (text.isNotBlank()) done += text
    }

    fun finish(): List<String> {
        flush()
        return done
    }
}

/** One node, as blocks and inline runs on [out]. */
private fun Node.render(out: Blocks) {
    when (this) {
        is TextNode -> out.inline(text())
        is Element -> renderElement(out)
        else -> Unit
    }
}

@Suppress("CyclomaticComplexMethod")
private fun Element.renderElement(out: Blocks) {
    when (val name = normalName()) {
        in DROPPED -> Unit
        // A line inside a run rather than a break between blocks: an address block and a
        // poem both use it, and both read wrongly when every line becomes a paragraph.
        "br" -> out.inline("\n")
        "a" -> out.inline(asLink())
        "code" -> out.inline("`${text().collapsed()}`")
        "hr" -> out.block(RULE)
        in HEADINGS -> out.block("${"#".repeat(name.last().digitToInt())} ${inlineOf()}")
        "pre" -> out.block(fenced())
        "ul", "ol" -> out.block(listBlock(depth = 0))
        "table" -> out.block(tableBlock())
        "blockquote" -> out.block(quoted())
        // Its own block, whatever surrounds it, and whatever it contains.
        in PARAGRAPHS -> {
            out.flush()
            childNodes().forEach { it.render(out) }
            out.flush()
        }
        // Everything else is a wrapper: `div`, `section`, `span`, `strong`, `em`. Recursing
        // without flushing is what keeps a sentence broken across a span in one piece.
        else -> childNodes().forEach { it.render(out) }
    }
}

/**
 * The text of an element and its descendants as one inline run.
 *
 * Used where a construct is a single line by definition: a heading, a list item's own text,
 * a table cell. Block children are skipped rather than flattened into it, because a list
 * nested inside a list item is rendered by the list code that owns it and would otherwise
 * appear twice.
 */
private fun Element.inlineOf(): String {
    val out = StringBuilder()
    for (child in childNodes()) {
        when (child) {
            is TextNode -> out.append(child.text())
            is Element -> when (child.normalName()) {
                in DROPPED, in NESTED_BLOCKS -> Unit
                "br" -> out.append('\n')
                "a" -> out.append(child.asLink())
                "code" -> out.append('`').append(child.text().collapsed()).append('`')
                else -> out.append(child.inlineOf())
            }

            else -> Unit
        }
    }
    return out.toString().collapsed()
}

/**
 * A link as its label and, when there is one worth following, its address.
 *
 * Only `http` and `https` survive as addresses. A `mailto:`, a `javascript:` or a bare `#`
 * fragment is somewhere this tool cannot go, so printing it spends tokens on a dead end and
 * invites the model to try. The label is kept in every case, because the words in a link
 * are part of the sentence around it.
 */
private fun Element.asLink(): String {
    val label = inlineOf()
    if (label.isEmpty()) return ""
    // The written href decides, before resolution, because resolving hides the case this
    // has to catch: `#top` against a base becomes `https://example.com#top`, which passes
    // every test for an address and is the page the model is already reading.
    val written = attr("href").trim()
    if (written.isEmpty() || written.startsWith("#")) return label
    val href = absUrl("href").trim()
    return when {
        !href.startsWith("http") -> label
        // A link whose text is already its address is printed once rather than twice.
        href == label -> label
        else -> "[$label]($href)"
    }
}

/**
 * A `<pre>` as a fenced block, with its whitespace intact.
 *
 * `wholeText` rather than `text`, which is the whole point of the element: indentation and
 * line breaks are the content in a code sample, and the collapsing every other branch here
 * does would turn a shell session into one line.
 */
private fun Element.fenced(): String {
    val code = wholeText().trim('\n', ' ')
    return if (code.isEmpty()) "" else "$FENCE\n$code\n$FENCE"
}

/**
 * A list as its items, one per line, nested lists indented under the item that holds them.
 *
 * Numbered from one per list rather than per document, and counted here rather than taken
 * from the markup, because a page that starts a list at `<li value="7">` is rare and a page
 * whose items are numbered by CSS is not.
 */
private fun Element.listBlock(depth: Int): String {
    val ordered = normalName() == "ol"
    val indent = "  ".repeat(depth)
    val lines = mutableListOf<String>()
    var number = 1
    for (item in children().filter { it.normalName() == "li" }) {
        val marker = if (ordered) "${number++}. " else "- "
        val own = item.inlineOf()
        if (own.isNotEmpty()) {
            // A wrapped item stays one item: its continuation lines are indented under the
            // marker rather than left at column zero looking like the next item's text.
            lines += own.lines().mapIndexed { index, line ->
                if (index == 0) "$indent$marker$line" else "$indent  $line"
            }
        }
        item.children()
            .filter { it.normalName() == "ul" || it.normalName() == "ol" }
            .forEach { nested -> lines += nested.listBlock(depth + 1) }
    }
    return lines.joinToString("\n")
}

/**
 * A table as pipe-separated rows, with a separator under the header when it has one.
 *
 * The one construct here whose whole meaning is positional: a specification table read as
 * prose is a list of words in which nothing says which value belongs to which property, and
 * that is exactly the page somebody fetches by address. `select` rather than direct children
 * because a `<tbody>` is inserted by every parser whether the author wrote one or not.
 */
private fun Element.tableBlock(): String {
    val lines = mutableListOf<String>()
    var headerWritten = false
    for (row in select("tr")) {
        val cells = row.children().filter { it.normalName() == "td" || it.normalName() == "th" }
        if (cells.isEmpty()) continue
        lines += cells.joinToString(" | ", "| ", " |") { it.inlineOf().escapedForCell() }
        if (!headerWritten && cells.any { it.normalName() == "th" }) {
            lines += cells.joinToString(" | ", "| ", " |") { "---" }
            headerWritten = true
        }
    }
    return lines.joinToString("\n")
}

/**
 * A cell's text, safe to sit between pipes.
 *
 * A bare `|` inside a cell splits it in two and every row after it reads with its columns
 * shifted, which is worse than the flattening this whole file replaces: a wrong table is
 * read confidently. A newline from a `<br>` does the same thing to the row, so it becomes a
 * space. Empty cells keep their column with a space, or the row loses a position.
 */
private fun String.escapedForCell(): String =
    replace("|", "\\|").replace("\n", " ").collapsed().ifEmpty { " " }

/** A quote as its blocks, each line marked, so its edges survive the flattening around it. */
private fun Element.quoted(): String {
    val inner = Blocks()
    childNodes().forEach { it.render(inner) }
    return inner.finish()
        .joinToString("\n\n")
        .lines()
        .joinToString("\n") { line -> if (line.isEmpty()) ">" else "> $line" }
}

/**
 * Runs of spaces and tabs squeezed to one, with the line breaks left alone.
 *
 * Not `\s+`, which would take the newlines a `<br>` and a fenced block put there on purpose.
 * Every line is then trimmed, because indentation in the source of an HTML file is the
 * author's formatting rather than the document's content.
 */
private fun String.collapsed(): String = replace(HORIZONTAL_SPACE, " ")
    .lines()
    .joinToString("\n") { it.trim() }
    .trim()

/** Three or more newlines are the seam between two blocks, not a gap somebody wrote. */
private fun String.collapseBlankRuns(): String = replace(BLANK_RUN, "\n\n").trim()

/**
 * Elements whose contents are never prose.
 *
 * Overlaps the furniture pass on purpose. That one runs on the markup as a string and takes
 * whole elements out before this sees them, and the two are not the same list: this one is
 * about what cannot be read, that one is about what is not worth reading. A page that
 * reaches here with a `<script>` still in it, because its opening tag never closed, must
 * still not have its source code read aloud to the model.
 */
private val DROPPED = setOf(
    "script", "style", "noscript", "template", "svg", "canvas", "iframe", "object", "embed",
    "img", "picture", "video", "audio", "source", "track", "map", "area", "input", "button",
    "select", "option", "textarea", "label", "head", "title", "meta", "link", "base",
)

/** Rendered by the code that owns them, so an inline pass must not flatten them in as well. */
private val NESTED_BLOCKS = setOf("ul", "ol", "table", "pre", "blockquote")

private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

/**
 * Elements that are a block wherever they appear.
 *
 * `div` and `span` are absent deliberately. They say nothing about the document and a real
 * page nests them dozens deep around a single sentence, so treating each as a boundary cuts
 * ordinary prose into one-word blocks. The sectioning elements here are the opposite case:
 * an author writes `<section>` or `<article>` to mean a part of the document, and they do
 * not nest without reason.
 */
private val PARAGRAPHS = setOf(
    "p", "li", "dt", "dd", "figcaption", "caption", "tr", "th", "td", "address", "form",
    "fieldset", "legend", "details", "summary", "article", "section", "main", "header",
    "footer", "aside", "nav",
)

private const val FENCE = "```"
private const val RULE = "---"

/** Whitespace that is not a line break. */
private val HORIZONTAL_SPACE = Regex("[^\\S\\n]+")

private val BLANK_RUN = Regex("\n{3,}")

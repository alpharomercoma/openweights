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

/**
 * A reply with its Markdown syntax taken off, for pasting somewhere that cannot render it.
 *
 * A model reply is Markdown source and [MarkdownText] is what turns it into something to
 * read. Copying handed over the source instead, so a paragraph the reader had been looking
 * at as bold arrived in a text message as literal asterisks. Source is the right thing to
 * give a Markdown editor and the wrong thing to give anything else, which is why there are
 * now two copy actions rather than one that has to guess which was meant.
 *
 * The rule throughout is to produce what the renderer draws. Where the renderer shows
 * structure with type rather than with characters — headings, emphasis, code spans — the
 * characters go. Where it draws a mark the reader can see, plain text keeps a mark of its
 * own: a bullet stays a bullet, a checkbox stays a box, a table stays a table, because a
 * list whose bullets have been stripped is no longer a list.
 *
 * Nothing inside a fence is touched but the fence line itself. A code block is the one
 * place a reader is being shown characters on purpose, and an asterisk in it is an asterisk.
 */
fun String.markdownToPlainText(): String {
    var inside = false
    return withCheckboxes()
        .lineSequence()
        .mapNotNull { line ->
            when {
                // Dropped, language tag and all: the renderer draws that as a header above
                // the block rather than as part of it.
                line.trimStart().startsWith(FENCE) -> null.also { inside = !inside }
                inside -> line
                else -> line.plainProseLine()
            }
        }
        .joinToString("\n")
        .collapseBlankRuns()
        .trim()
}

/** One line of prose: the marks that open a block, then the spans inside it. */
private fun String.plainProseLine(): String = this
    .replace(HEADING, "")
    .replace(QUOTE, "")
    .let { line -> BULLET.replace(line) { "${it.groupValues[1]}$DOT " } }
    .plainSpans()

/**
 * The inline marks, in the one order that does not eat its own output.
 *
 * Code spans go first and put their contents beyond the reach of everything after them: a
 * model writing `**kwargs` inside backticks means those asterisks literally, and an
 * emphasis pass running first would have already taken them. The placeholder is a control
 * character, so no reply can contain one and be mistaken for a masked span.
 */
private fun String.plainSpans(): String {
    val spans = mutableListOf<String>()
    val masked = CODE_SPAN.replace(this) { match ->
        spans += match.groupValues[2]
        "$MASK${spans.lastIndex}$MASK"
    }
    val flattened = masked
        // Images before links: the renderer turns `![alt](url)` into a link and draws that,
        // so running the link rule first would leave the bang stranded in front of it.
        .replace(IMAGE) { it.linkText() }
        .replace(LINK) { it.linkText() }
        .replace(AUTOLINK, "$1")
        .replace(STRIKE, "$1")
        .replace(STRONG, "$1")
        .replace(EMPHASIS_STAR, "$1")
        // Underscores last and only at a word boundary, or `some_long_name` loses its middle.
        .replace(EMPHASIS_UNDERSCORE, "$1")
    return MASKED.replace(flattened) { spans[it.groupValues[1].toInt()] }
}

/**
 * A link as plain text, keeping the address when it is not already the label.
 *
 * Dropping the URL is the tempting reading of "what the renderer draws" and the wrong one.
 * On screen the label is live and a tap goes somewhere; pasted into a mail, a label with no
 * address behind it is a sentence that used to be a way to get somewhere and now is not.
 */
private fun MatchResult.linkText(): String {
    val label = groupValues[1].trim()
    val url = groupValues[2].trim()
    return when {
        url.isEmpty() -> label
        label.isEmpty() || label == url -> url
        else -> "$label ($url)"
    }
}

/** Three or more newlines are a gap left by a dropped fence, not a break somebody wrote. */
private fun String.collapseBlankRuns(): String = replace(BLANK_RUN, "\n\n")

private const val FENCE = "```"

/** Stands in for a code span while the other rules run. No reply contains a NUL. */
private const val MASK = "\u0000"

private val MASKED = Regex("\u0000(\\d+)\u0000")

/** `# ` through `###### `, at the start of a line. */
private val HEADING = Regex("^ {0,3}#{1,6}\\s+")

/** `> `, however many levels deep. */
private val QUOTE = Regex("^ {0,3}(?:>\\s?)+")

/** An unordered bullet, keeping the indent that says how deep it is. */
private val BULLET = Regex("^(\\s*)[-*+]\\s+")

/** `` `code` ``, and the doubled form used to quote a backtick. */
private val CODE_SPAN = Regex("(`+)([^`]|[^`].*?[^`])\\1(?!`)")

private val IMAGE = Regex("""!\[([^\]]*)]\(\s*([^)\s]*)(?:\s+"[^"]*")?\s*\)""")
private val LINK = Regex("""(?<!!)\[([^\]]*)]\(\s*([^)\s]*)(?:\s+"[^"]*")?\s*\)""")

/** `<https://example.com>`, which GitHub Markdown draws as the bare address. */
private val AUTOLINK = Regex("<((?:https?|mailto):[^>\\s]+)>")

private val STRIKE = Regex("~~(.+?)~~")

/** `**bold**` and `__bold__` together; the renderer draws both the same way. */
private val STRONG = Regex("(?:\\*\\*|__)(.+?)(?:\\*\\*|__)")

/** `*italic*`, refusing to match across the space that would make it a stray asterisk. */
private val EMPHASIS_STAR = Regex("(?<!\\*)\\*(?!\\s)([^*]+?)(?<!\\s)\\*(?!\\*)")

/** `_italic_`, only where both marks sit at a word boundary, sparing `snake_case`. */
private val EMPHASIS_UNDERSCORE = Regex("(?<!\\w)_(?!\\s)([^_]+?)(?<!\\s)_(?!\\w)")

private val BLANK_RUN = Regex("\n{3,}")

/** The bullet a reader sees, standing in for the one the renderer draws. */
private const val DOT = "•"

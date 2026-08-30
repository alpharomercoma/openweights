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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import io.github.alpharomercoma.openweights.core.designsystem.theme.CodeTextStyle
import io.github.alpharomercoma.openweights.core.designsystem.theme.LocalIsDarkTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.impl.MathParser

/**
 * Renders a model reply as Markdown.
 *
 * Local models produce the same Markdown that hosted ones do: headings, lists, tables,
 * and above all fenced code, so rendering it is not a nicety. Code blocks get a header
 * with the language and a copy button, which is the one interaction people reliably want
 * from a model's output.
 */
@Composable
fun MarkdownText(content: String, modifier: Modifier = Modifier) {
    // The theme's own answer, not the platform's. Reading isSystemInDarkTheme() here meant
    // that forcing the app light while the phone was dark left every code block with dark
    // syntax colours on a white card, because this is the one colour system the theme does
    // not own and it was asking a different question from everything around it.
    val darkTheme = LocalIsDarkTheme.current
    val highlightsBuilder = remember(darkTheme) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = darkTheme))
    }

    // retainState keeps the last render on screen while the next parse runs. The default
    // is false, which swaps the whole reply for an empty loading slot every time the text
    // changes. During streaming the text changes constantly, so the reply blanked and
    // reappeared on every update. That was the flicker.
    // Closed, if the model has not closed it yet.
    //
    // A fence with no end drops its last line: the parser has an unterminated block and
    // holds the line it is still reading. That is every code block for the whole time one
    // is being written, so a reply streamed a token at a time showed its code one line
    // behind the model, and the missing line only appeared when the closing fence did.
    // Adding the fence the model is about to send anyway costs nothing and is exactly what
    // the finished text will say.
    val closed = remember(content) {
        content.withClosedFence().withLinkedImages().withCheckboxes()
    }
    val state = rememberMarkdownState(
        content = closed,
        retainState = true,
        flavour = PROSE,
    )
    // How wide the widest table needs to be, in characters, or zero when they all fit.
    val tableChars = remember(closed) { closed.wideMarkdownTableChars() }
    val defaults = markdownComponents()

    val render: @Composable () -> Unit = {
        Markdown(
            state,
            modifier = modifier,
            colors = markdownColor(
                text = MaterialTheme.colorScheme.onBackground,
                codeBackground = MaterialTheme.colorScheme.surfaceContainer,
                inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainer,
                dividerColor = MaterialTheme.colorScheme.outline,
            ),
            typography = markdownTypography(
                text = MaterialTheme.typography.bodyLarge,
                paragraph = MaterialTheme.typography.bodyLarge,
                h1 = MaterialTheme.typography.headlineSmall,
                h2 = MaterialTheme.typography.titleLarge,
                h3 = MaterialTheme.typography.titleMedium,
                code = CodeTextStyle,
                inlineCode = CodeTextStyle,
                quote = MaterialTheme.typography.bodyLarge,
                list = MaterialTheme.typography.bodyLarge,
            ),
            components = markdownComponents(
                codeBlock = {
                    MarkdownHighlightedCodeBlock(
                        content = it.content,
                        node = it.node,
                        highlightsBuilder = highlightsBuilder,
                        showHeader = true,
                    )
                },
                codeFence = {
                    MarkdownHighlightedCodeFence(
                        content = it.content,
                        node = it.node,
                        highlightsBuilder = highlightsBuilder,
                        showHeader = true,
                    )
                },
                table = { model ->
                    // Given a width, and dragged rather than squeezed.
                    //
                    // The renderer fits a table to whatever width it is handed and cuts
                    // each cell to one line with an ellipsis, so a description longer than
                    // its column was not merely hidden, it was gone: no scrolling reached
                    // it because there was nothing wider to scroll to. `MarkdownDimens`
                    // looks like the way to say otherwise and is ignored — proved on a
                    // device by rendering the same table at a 900dp cell width and getting
                    // an identical bitmap. So the width is imposed here, where it holds,
                    // and the surface to drag it on goes around the outside.
                    if (tableChars > 0) {
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            Box(Modifier.width(CHARACTER * tableChars)) {
                                defaults.table(model)
                            }
                        }
                    } else {
                        defaults.table(model)
                    }
                },
            ),
        )
    }
    render()
}

/**
 * About one character of body text, used to turn a cell's length into a width.
 *
 * Rough on purpose: the point is a column wide enough for the sentence a model put in it,
 * and being a little generous costs a drag while being mean costs the words.
 */
private val CHARACTER = 8.5.dp

/**
 * GitHub Markdown with the maths taken out.
 *
 * The default flavour runs a `MathParser`, which swallows everything between two dollar
 * signs on a line and hands back a node this renderer has no component for — so the text
 * is not merely unstyled, it is deleted. That costs nothing when a model writes a formula
 * nobody expected to render, and it costs a sentence when a model writes about money:
 * "it costs ${'$'}5 and then ${'$'}10 for the pair" rendered as "it costs 10 for the pair".
 *
 * Everything else GitHub adds — tables, strikethrough, autolinks — is kept, because those
 * are the parts models actually use.
 */
private val PROSE: MarkdownFlavourDescriptor = object : GFMFlavourDescriptor() {
    override val sequentialParserManager = object : SequentialParserManager() {
        override fun getParserSequence(): List<SequentialParser> =
            GFMFlavourDescriptor().sequentialParserManager.getParserSequence()
                .filterNot { parser -> parser is MathParser }
    }
}

/**
 * The same text with a closing fence added, when one is open.
 *
 * Counts the fence markers rather than tracking state: a line whose first non-space
 * characters are three backticks either opens a block or closes one, so an odd count means
 * one is still open. A fence inside a fence is not a thing Markdown has, which is what
 * makes counting sufficient.
 */
internal fun String.withClosedFence(): String {
    val fences = lineSequence().count { it.trimStart().startsWith(FENCE) }
    return if (fences % 2 == 0) this else "$this\n$FENCE"
}

/**
 * Applies [transform] to the prose, and leaves code exactly as it was written.
 *
 * Everything below rewrites Markdown the renderer cannot draw into Markdown it can, which
 * is only ever right outside a fence: a code block that happens to contain `![alt](url)`
 * or `- [ ]` is showing somebody that text on purpose, and quietly editing it would be the
 * one thing a code block promises not to do.
 */
private fun String.outsideFences(transform: (String) -> String): String {
    var inside = false
    return lineSequence().joinToString("\n") { line ->
        when {
            line.trimStart().startsWith(FENCE) -> line.also { inside = !inside }
            inside -> line
            else -> transform(line)
        }
    }
}

/**
 * Turns a picture into the link it came from.
 *
 * This app cannot show a remote image and should not try: the URL comes from the model,
 * and fetching it would be the one thing an app that answers on the phone must not do
 * quietly. The renderer's answer was worse than not drawing it — its no-op transformer
 * still reserves a square, so an invented picture left a tall empty gap in the middle of a
 * reply with nothing to say what had been there.
 *
 * A link keeps both halves of what the model wrote: the alt text, which is the description,
 * and the address, which is the only way anyone could go and look. The alt text is
 * preferred as the label because that is what it is for; the URL stands in when there is
 * none, since a bare link still reads as something.
 */
internal fun String.withLinkedImages(): String = outsideFences { line ->
    IMAGE.replace(line) { match ->
        val (alt, url) = match.destructured
        "[${alt.ifBlank { url }}]($url)"
    }
}

/**
 * Draws the box a task list asks for, which the renderer otherwise drops.
 *
 * GFM parses `- [ ] thing` into a task item and this renderer has no component for one, so
 * it came out as the literal characters `[ ]` with the bullet gone — the one part of a
 * list that says it is a list. The box and tick are written into the text instead, where
 * they are just characters and cannot be dropped by anything.
 */
internal fun String.withCheckboxes(): String = outsideFences { line ->
    TASK.replace(line) { match ->
        val (marker, state) = match.destructured
        "$marker${if (state.equals("x", ignoreCase = true)) TICKED else EMPTY} "
    }
}

private const val FENCE = "```"

/** `![alt](url)`, with an optional title the renderer never showed anyway. */
private val IMAGE = Regex("""!\[([^\]]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")

/** A list item that opens with a checkbox, at any indent and under any bullet. */
private val TASK = Regex("""^(\s*[-*+]\s+)\[([ xX])]\s""")

private const val EMPTY = "\u2610"
private const val TICKED = "\u2611"

/**
 * How many characters wide the widest table needs to be, or zero if every table fits.
 *
 * Every column gets the same width from the renderer, so the table needs the widest single
 * cell in it multiplied by the number of columns; sizing to the average leaves the longest
 * sentence ellipsised, which is the whole failure being fixed. Capped per cell, because a
 * model that writes a paragraph into one cell should not make the other columns unreachable.
 */
internal fun String.wideMarkdownTableChars(): Int {
    val lines = lines()
    return (0 until lines.lastIndex).maxOfOrNull { index ->
        val divider = lines[index + 1]
        if (!divider.trim().matches(TABLE_DIVIDER)) {
            return@maxOfOrNull 0
        }

        val rows = lines.drop(index).takeWhile { it.markdownTableCells().size >= 2 }
        val cells = rows
            .filterNot { it.trim().matches(TABLE_DIVIDER) }
            .map(String::markdownTableCells)
        val columns = cells.maxOfOrNull(List<String>::size) ?: return@maxOfOrNull 0
        val estimatedCharacters = (0 until columns).sumOf { column ->
            cells.maxOfOrNull { row -> row.getOrNull(column)?.length ?: 0 }
                ?.coerceIn(MIN_TABLE_COLUMN_CHARS, MAX_TABLE_COLUMN_CHARS)
                ?: MIN_TABLE_COLUMN_CHARS
        } + columns * TABLE_COLUMN_PADDING_CHARS
        if (columns >= WIDE_TABLE_COLUMNS || estimatedCharacters > WIDE_TABLE_CHARACTERS) {
            val widestCell = cells.flatten().maxOfOrNull(String::length) ?: 0
            columns * (widestCell + TABLE_COLUMN_PADDING_CHARS)
                .coerceIn(MIN_TABLE_COLUMN_CHARS, MAX_CELL_CHARS)
        } else {
            0
        }
    } ?: 0
}

private fun String.markdownTableCells(): List<String> =
    trim().trim('|').split('|').map(String::trim)

private val TABLE_DIVIDER = Regex("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?")
private const val WIDE_TABLE_COLUMNS = 4
private const val WIDE_TABLE_CHARACTERS = 32
private const val MIN_TABLE_COLUMN_CHARS = 4
private const val MAX_TABLE_COLUMN_CHARS = 24
private const val TABLE_COLUMN_PADDING_CHARS = 2

/** Long enough for a sentence, short enough that four of them can still be dragged past. */
private const val MAX_CELL_CHARS = 44

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun MarkdownTextPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        MarkdownText(
            """
            A **KV cache** stores past attention tensors.

            ```kotlin
            val cache = KvCache(layers = 30)
            ```
            """.trimIndent(),
        )
    }
}

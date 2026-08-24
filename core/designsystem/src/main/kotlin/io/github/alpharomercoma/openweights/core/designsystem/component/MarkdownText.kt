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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import io.github.alpharomercoma.openweights.core.designsystem.theme.CodeTextStyle
import io.github.alpharomercoma.openweights.core.designsystem.theme.LocalIsDarkTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme

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
    val state = rememberMarkdownState(content = content, retainState = true)
    val wideTable = remember(content) { content.containsWideMarkdownTable() }
    val markdownModifier = if (wideTable) {
        modifier.horizontalScroll(rememberScrollState())
    } else {
        modifier
    }

    val render: @Composable () -> Unit = {
        Markdown(
            state,
            modifier = markdownModifier,
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
            ),
        )
    }
    if (wideTable) {
        CompositionLocalProvider(LocalMarkdownDimens provides WIDE_TABLE_DIMENS) { render() }
    } else {
        render()
    }
}

/** Gives wide tables a real horizontal surface instead of the renderer's one-line ellipsis. */
private val WIDE_TABLE_DIMENS = object : MarkdownDimens {
    override val dividerThickness = 1.dp
    override val codeBackgroundCornerSize = 6.dp
    override val blockQuoteThickness = 3.dp
    override val tableMaxWidth = 4_096.dp
    override val tableCellWidth = 640.dp
    override val tableCellPadding = 8.dp
    override val tableCornerSize = 6.dp
}

private fun String.containsWideMarkdownTable(): Boolean {
    val lines = lines()
    return lines.zipWithNext().any { (header, divider) ->
        header.count { it == '|' } >= 2 &&
            divider.trim().matches(TABLE_DIVIDER) &&
            header.split('|').any { it.trim().length > 24 }
    }
}

private val TABLE_DIVIDER = Regex("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?")

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

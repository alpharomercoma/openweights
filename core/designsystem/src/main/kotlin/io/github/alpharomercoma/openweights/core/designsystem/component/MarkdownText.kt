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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import io.github.alpharomercoma.openweights.core.designsystem.theme.CodeTextStyle
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme

/**
 * Renders a model reply as Markdown.
 *
 * Local models produce the same Markdown that hosted ones do — headings, lists, tables,
 * and above all fenced code — so rendering it is not a nicety. Code blocks get a header
 * with the language and a copy button, which is the one interaction people reliably want
 * from a model's output.
 */
@Composable
fun MarkdownText(content: String, modifier: Modifier = Modifier) {
    val darkTheme = isSystemInDarkTheme()
    val highlightsBuilder = remember(darkTheme) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = darkTheme))
    }

    Markdown(
        content = content,
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
        ),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0E11)
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

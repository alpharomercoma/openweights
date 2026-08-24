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

package io.github.alpharomercoma.openweights.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.designsystem.component.MarkdownText
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A wide table may scroll sideways. The sentence next to it may not.
 *
 * Giving the whole reply a horizontal scroll is the obvious way to make a table fit, and it
 * measures every paragraph in that reply against an unbounded width: nothing wraps, and each
 * sentence becomes one line running off the side of the phone. Only the table asked for the
 * extra width, so only the table gets it.
 *
 * Asserted on the shape of the tree rather than on measured text, because Robolectric does
 * not lay out glyphs and a width assertion here would pass whatever the code did. Which node
 * carries the scroll is real either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class MarkdownTableTest {
    @get:Rule
    val compose = createComposeRule()

    private val paragraph = "This sentence sits above a table that is wider than the phone."

    private val reply = """
        $paragraph

        | Runtime and where it runs | What it will not do on a phone | Measured decode |
        | --- | --- | --- |
        | llama.cpp on the CPU with four threads | nothing, the baseline | 24 tok/s |
    """.trimIndent()

    @Test
    fun `only the table scrolls sideways, not the sentence beside it`() {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Box(Modifier.width(360.dp)) { MarkdownText(content = reply) }
            }
        }

        // Waited for, not assumed. `rememberMarkdownState(retainState = true)` parses off
        // the composition, so the table is not in the tree on the first frame: querying
        // straight away found nothing and passed for the wrong reason, in whichever order
        // the suite happened to run this class.
        compose.waitUntil {
            compose.onAllNodes(hasText("Measured decode", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }

        val scrollers = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
        ).fetchSemanticsNodes()

        // The table has to have somewhere to go, or a wide one is simply cut off.
        assertThat(scrollers).isNotEmpty()
        scrollers.forEach { assertThat(it.textUnder()).doesNotContain(paragraph) }
    }

    @Test
    fun `the assertion has teeth`() {
        // The shape this guards against, built here rather than by reverting the fix: a
        // scroll around the whole reply. Without a table, so that this measures the check
        // and not the renderer. If the check above can pass either way it is worth nothing.
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Box(Modifier.width(360.dp)) {
                    MarkdownText(
                        content = paragraph,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }

        compose.waitUntil {
            compose.onAllNodes(hasText(paragraph, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val caught = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
        ).fetchSemanticsNodes().any { paragraph in it.textUnder() }

        assertThat(caught).isTrue()
    }

    private fun SemanticsNode.textUnder(): List<String> =
        (config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
            children.flatMap { it.textUnder() }
}

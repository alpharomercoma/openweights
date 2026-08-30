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

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alpharomercoma.openweights.core.designsystem.component.MarkdownText
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Renders deliberately hostile Markdown at the width a reply actually gets, on a device,
 * and writes a PNG of each case so the result can be looked at rather than asserted about.
 *
 * On a device because the questions here are pixel questions — does a 90-character
 * identifier push the bubble off the screen, does a code fence clip or scroll, does a
 * table with inline pipes survive — and Robolectric measures every glyph at a fictional
 * width, so a green host run would prove nothing about any of them.
 *
 * The cases are what a local model actually emits: half-finished fences mid-stream,
 * `snake_case` that markdown wants to read as emphasis, tables with code in the cells,
 * lists nested four deep, and the long unbroken tokens that URLs and hashes are.
 */
class MarkdownTortureOnDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val out: File by lazy {
        File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null),
            "markdown-torture",
        ).apply { mkdirs() }
    }

    @Test
    fun renderEveryCase() {
        // One composition, driven through every case. The rule allows setContent once per
        // test, and a test per case would be seventeen activity launches for no gain.
        var markdown by mutableStateOf(CASES.first().second)
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        // The same 16dp the conversation list gives a reply.
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    MarkdownText(markdown)
                }
            }
        }

        CASES.forEach { (name, source) ->
            compose.runOnUiThread { markdown = source }
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        // The listing the harness reads back, so a case that produced no file is visible.
        File(out, "index.txt").writeText(CASES.joinToString("\n") { it.first })
    }

    /**
     * Whether a code line longer than the screen can actually be reached.
     *
     * A screenshot cannot answer this: a block that clips its overflow and a block that
     * scrolls it look identical until something drags them. So this drags, and compares
     * the pixels before and after. If they differ the content moved and the rest of the
     * line is reachable; if they are identical it is cut off and gone.
     */
    @Test
    fun aLongCodeLineCanBeReached() {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    MarkdownText(CASES.first { it.first.startsWith("04") }.second)
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onRoot().captureToImage().asAndroidBitmap().copy(
            Bitmap.Config.ARGB_8888,
            false,
        )
        File(out, "scroll-before.png").outputStream().use {
            before.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        compose.onNodeWithText("aFunctionWithAnExtremelyLongSignature", substring = true)
            .performTouchInput { swipeLeft() }
        compose.waitForIdle()

        val after = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "scroll-after.png").outputStream().use {
            after.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(out, "scroll-result.txt").writeText(
            if (before.sameAs(after)) {
                "IDENTICAL - the long line is clipped and unreachable"
            } else {
                "MOVED - the block scrolls, the rest of the line is reachable"
            },
        )
    }

    /**
     * Which half of the bold problem is broken: the font, or the markdown.
     *
     * Draws the same sentence three ways — plain Compose text asking for Bold on the very
     * style Markdown paragraphs use, the same asking for SemiBold, and Markdown's own
     * `**strong**` — so the answer is a comparison rather than a guess.
     */
    @Test
    fun whereBoldIsLost() {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    val ink = MaterialTheme.colorScheme.onBackground
                    Text(
                        "normal weight text",
                        color = ink,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "compose Bold text",
                        color = ink,
                        style = MaterialTheme.typography.bodyLarge
                            .copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        "compose SemiBold text",
                        color = ink,
                        style = MaterialTheme.typography.bodyLarge
                            .copy(fontWeight = FontWeight.SemiBold),
                    )
                    MarkdownText("**markdown strong text**")
                    MarkdownText("normal then **strong** then normal")
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "bold-diagnosis.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /**
     * Which way of declaring a variable font actually produces weight on this device.
     *
     * Four constructions of the same file, asked for Bold. Whichever renders heavier is
     * the one the theme should use; the others are the reasons bold has been invisible.
     */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    @Test
    fun whichFontFamilyCanActuallyGoBold() {
        val res = io.github.alpharomercoma.openweights.core.designsystem.R.font.hanken_grotesk
        fun weightAxis(w: Int) = androidx.compose.ui.text.font.FontVariation.Settings(
            androidx.compose.ui.text.font.FontVariation.weight(w),
        )
        // A: what the theme does today — several weights, one resource, variation settings.
        val multi = androidx.compose.ui.text.font.FontFamily(
            androidx.compose.ui.text.font.Font(
                res,
                FontWeight.Normal,
                variationSettings = weightAxis(400),
            ),
            androidx.compose.ui.text.font.Font(
                res,
                FontWeight.Medium,
                variationSettings = weightAxis(500),
            ),
            androidx.compose.ui.text.font.Font(
                res,
                FontWeight.SemiBold,
                variationSettings = weightAxis(600),
            ),
            androidx.compose.ui.text.font.Font(
                res,
                FontWeight.Bold,
                variationSettings = weightAxis(700),
            ),
        )
        // B: only the regular face declared, so Compose has to synthesise bold.
        val synth = androidx.compose.ui.text.font.FontFamily(
            androidx.compose.ui.text.font.Font(res, FontWeight.Normal),
        )
        // C: one face, declared AS bold, pinned to 700 by its variation settings.
        val pinned = androidx.compose.ui.text.font.FontFamily(
            androidx.compose.ui.text.font.Font(
                res,
                FontWeight.Bold,
                variationSettings = weightAxis(700),
            ),
        )
        // D: the platform's own sans, as a control that bold is possible at all here.
        val system = androidx.compose.ui.text.font.FontFamily.SansSerif
        // E: a font-family XML that carries the axis the way the framework applies it.
        val xml = androidx.compose.ui.text.font.FontFamily(
            androidx.compose.ui.text.font.Font(res, FontWeight.Normal),
            androidx.compose.ui.text.font.Font(
                io.github.alpharomercoma.openweights.core.designsystem.R.font.hanken_grotesk_bold,
                FontWeight.Bold,
            ),
        )

        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    val ink = MaterialTheme.colorScheme.onBackground
                    listOf(
                        "A multi" to multi,
                        "B synth" to synth,
                        "C pinned" to pinned,
                        "D system" to system,
                        "E xml" to xml,
                    ).forEach { (label, family) ->
                        Text(
                            "$label regular",
                            color = ink,
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = family),
                        )
                        Text(
                            "$label BOLD",
                            color = ink,
                            style = MaterialTheme.typography.bodyLarge
                                .copy(fontFamily = family, fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "font-families.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /**
     * Whether a table that overflows can be reached, when the wide-table heuristic says no.
     *
     * Three columns whose estimated width lands exactly on the threshold rather than over
     * it, so the horizontal surface is not applied. Same method as the code block: drag it
     * and compare the pixels, because clipped and scrollable look identical standing still.
     */
    @Test
    fun aTableJustUnderTheThresholdCanBeReached() {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    MarkdownText(NARROW_ENOUGH_TABLE)
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onRoot().captureToImage().asAndroidBitmap()
            .copy(Bitmap.Config.ARGB_8888, false)
        compose.onNodeWithText("Verbose", substring = true).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val after = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "table-scroll.png").outputStream().use {
            after.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(out, "table-scroll-result.txt").writeText(
            if (before.sameAs(after)) {
                "IDENTICAL - the table is clipped and unreachable"
            } else {
                "MOVED - the table scrolls"
            },
        )
    }

    /**
     * The same renderer, pointed at what the models on this phone actually wrote.
     *
     * The cases above are markdown a person wrote to be difficult, which is the right way
     * to find edges but not evidence about the input this app really gets. These files
     * come from [MarkdownAbilityProbe] — real replies, including the malformed emphasis
     * and unbalanced asterisks a 1B model produces without meaning to.
     */
    @Test
    fun renderWhatTheModelsWrote() {
        val replies = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null),
            "markdown-replies",
        ).listFiles { f -> f.extension == "md" }.orEmpty().sortedBy { it.name }
        org.junit.Assume.assumeTrue("no replies captured yet", replies.isNotEmpty())

        var markdown by mutableStateOf(replies.first().readText())
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    MarkdownText(markdown)
                }
            }
        }
        replies.forEach { file ->
            compose.runOnUiThread { markdown = file.readText() }
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out, "reply-${file.nameWithoutExtension}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    /**
     * Whether the wide-table dimensions do anything at all.
     *
     * Renders the same table twice, the second time under deliberately enormous cell
     * widths supplied through the library's own composition local. If the two are
     * identical the dimensions are being ignored, and the whole wide-table path is
     * decoration; if they differ, the path works and the heuristic is what missed.
     */
    @Test
    fun doTableDimensDoAnything() {
        var wide by mutableStateOf(false)
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (wide) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            com.mikepenz.markdown.compose.LocalMarkdownDimens provides HUGE,
                        ) { MarkdownText(NARROW_ENOUGH_TABLE) }
                    } else {
                        MarkdownText(NARROW_ENOUGH_TABLE)
                    }
                }
            }
        }
        compose.waitForIdle()
        val plain = compose.onRoot().captureToImage().asAndroidBitmap()
            .copy(Bitmap.Config.ARGB_8888, false)
        compose.runOnUiThread { wide = true }
        compose.waitForIdle()
        val big = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "dimens-wide.png").outputStream().use {
            big.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(out, "dimens-result.txt").writeText(
            if (plain.sameAs(big)) {
                "IDENTICAL - table dimens are ignored"
            } else {
                "DIFFERENT - table dimens work"
            },
        )
    }

    private companion object {
        /** Deliberately enormous, to see whether the library honours these at all. */
        val HUGE = object : com.mikepenz.markdown.model.MarkdownDimens {
            override val dividerThickness = 1.dp
            override val codeBackgroundCornerSize = 6.dp
            override val blockQuoteThickness = 3.dp
            override val tableMaxWidth = 4_096.dp
            override val tableCellWidth = 900.dp
            override val tableCellPadding = 8.dp
            override val tableCornerSize = 6.dp
        }

        /** The flag table a model on this phone actually wrote, ellipsis and all. */
        val NARROW_ENOUGH_TABLE = """
            | Flag                  | Description                |
            |-----------------------|----------------------------|
            | `-c`                 | Connect to the specified interface |
            | `--configure`        | Apply configuration changes  |
            | `-v`                 | Verbose output             |
            | `--help`             | Show help information      |
        """.trimIndent()

        val CASES: List<Pair<String, String>> = listOf(
            "01-headings" to """
                # H1 heading
                ## H2 heading
                ### H3 heading
                #### H4 heading
                ##### H5 heading
                ###### H6 heading
                Body text after the headings.
            """.trimIndent(),

            "02-emphasis" to """
                **bold** and *italic* and ***both*** and ~~struck~~.

                A snake_case_identifier_here and another_one_with_more_parts should not
                turn into italics. Neither should 2*3*4 arithmetic.

                __Bold underscores__ and _single underscores_.

                Nested **bold with *italic* inside** and *italic with **bold** inside*.
            """.trimIndent(),

            "03-inline-code" to """
                Call `fun measure(input: String): Int` and then `val x = a || b`.

                Inline code holding a backtick: `` a ` b `` and an empty one: `` ` ``.

                A very long inline token:
                `androidx.compose.foundation.text.input.internal.ReceiveContentConfiguration`

                Code with a pipe `a|b` and an asterisk `a*b*c`.
            """.trimIndent(),

            "04-code-fence-long-lines" to """
                ```kotlin
                fun aFunctionWithAnExtremelyLongSignature(firstParameter: String, secondParameter: Int, thirdParameter: List<Map<String, Any>>): Result<Nothing> = TODO()
                val shortLine = 1
                ```
            """.trimIndent(),

            "05-code-fence-languages" to """
                ```python
                def greet(name: str) -> str:
                    return f"hello {name}"
                ```

                ```json
                {"key": "value", "n": 1, "nested": {"a": [1, 2, 3]}}
                ```

                ```
                no language given at all
                ```
            """.trimIndent(),

            "06-unclosed-fence" to """
                Here is the answer:

                ```kotlin
                fun half(): Int {
                    return 4
            """.trimIndent(),

            "07-nested-lists" to """
                - level one
                  - level two
                    - level three
                      - level four
                1. ordered one
                   1. ordered two
                      - mixed three
                - [ ] a task not done
                - [x] a task done
            """.trimIndent(),

            "08-table-simple" to """
                | Model | Size |
                | --- | --- |
                | LFM2.5 | 1.2B |
                | Qwen3 | 4B |
            """.trimIndent(),

            "09-table-wide" to """
                | Model | Params | Quant | Context | Prefill | Decode | RAM |
                | --- | --- | --- | --- | --- | --- | --- |
                | LFM2.5-1.2B-Instruct | 1.2B | Q4_K_M | 32768 | 142.0 tok/s | 36.1 tok/s | 1.1 GB |
                | Qwen3-4B-Instruct-2507 | 4B | Q4_K_M | 32768 | 61.4 tok/s | 12.8 tok/s | 2.9 GB |
            """.trimIndent(),

            "10-table-with-code" to """
                | Call | Meaning |
                | --- | --- |
                | `a \| b` | a pipe inside code |
                | `**not bold**` | literal asterisks |
                | **bold cell** | emphasis in a cell |
            """.trimIndent(),

            "11-long-unbroken" to """
                A URL nobody can wrap:
                https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-GGUF/resolve/main/LFM2.5-VL-1.6B-Q4_K_M.gguf?download=true

                A hash: 9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a39281706f5e4d3c2b1a0

                AnExtremelyLongCamelCaseIdentifierThatKeepsGoingAndGoingAndGoingForever
            """.trimIndent(),

            "12-quotes-rules" to """
                > A quotation that runs on for long enough to wrap onto a second line.
                >
                > > And a nested one inside it.

                ---

                Text after a horizontal rule.
            """.trimIndent(),

            "13-links-images" to """
                An [inline link](https://example.com) and a bare <https://example.com>.

                ![alt text](https://example.com/not-a-real-image.png)

                A [reference link][ref].

                [ref]: https://example.com
            """.trimIndent(),

            "14-html-and-math" to """
                Some <b>inline HTML</b> and a <br> break.

                Inline math ${'$'}E = mc^2${'$'} and display:

                ${'$'}${'$'}
                \sum_{i=1}^{n} x_i
                ${'$'}${'$'}
            """.trimIndent(),

            "15-mixed-cjk-rtl-emoji" to """
                English, then 日本語のテキストがここにあります, then العربية هنا, then emoji 🎉🔥✅.

                | Language | Sample |
                | --- | --- |
                | Japanese | 日本語のテキスト |
                | Arabic | نص عربي |
            """.trimIndent(),

            "16-streaming-partial" to """
                Here is a table being typed:

                | Model | Size |
                | --- | -
            """.trimIndent(),

            "18-unclosed-fence-long" to """
                ```kotlin
                val one = 1
                val two = 2
                val three = 3
                val four = 4
            """.trimIndent(),

            "19-closed-fence-control" to """
                ```kotlin
                val one = 1
                val two = 2
                val three = 3
                val four = 4
                ```
            """.trimIndent(),

            "20-dollars" to """
                Prices: it costs ${'$'}5 and then ${'$'}10 for the pair.

                One dollar sign alone: ${'$'} and that is all.

                Inline math ${'$'}E = mc^2${'$'} between words.

                Backslash command ${'$'}\alpha + \beta${'$'} between words.
            """.trimIndent(),

            "17-everything" to """
                # Report

                A **summary** with `inline code`, a [link](https://example.com), and *emphasis*.

                ## Findings

                1. First, with a nested list:
                   - `snake_case_name` stays literal
                   - a long token: androidx.compose.ui.platform.LocalSoftwareKeyboardController
                2. Second

                | Item | Value | Note |
                | --- | --- | --- |
                | Prefill | 142.0 tok/s | measured |
                | Decode | 36.1 tok/s | measured |

                ```kotlin
                fun main() {
                    println("done")
                }
                ```

                > Closing remark.
            """.trimIndent(),
        )
    }
}

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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * When the grader speaks and what it says.
 *
 * The browser is a fake that answers with whatever the test hands it and records what it
 * was asked to load; the rules under test are the grader's own: sites only, files that are
 * part of the site only, silence when the page is clean or the browser could not look, and
 * a result the model can act on when it did.
 */
class CanvasGraderTest {
    private val board = CanvasBoard()
    private val asked = mutableListOf<String>()
    private var answer: PageReport? = null
    private val grader = CanvasGrader(
        board,
        { path -> "http://127.0.0.1:1/key/$path" },
        object : PageChecker {
            override suspend fun check(url: String): PageReport? {
                asked += url
                return answer
            }
        },
    )

    @Test
    fun `says nothing when no canvas is showing`() = runTest {
        answer =
            PageReport(
                errors = listOf("ReferenceError: x is not defined (index.html:3)"),
                missing = emptyList(),
            )

        assertThat(grader.verdict("site/index.html")).isNull()
        assertThat(asked).isEmpty()
    }

    @Test
    fun `grades only sites, not documents or decks`() = runTest {
        answer = PageReport(errors = listOf("boom (index.html:1)"), missing = emptyList())
        board.show(CanvasKind.DOCUMENT, "notes/report.md", "notes")

        assertThat(grader.verdict("notes/report.md")).isNull()
        assertThat(asked).isEmpty()
    }

    @Test
    fun `a save outside the site is not the site's business`() = runTest {
        answer = PageReport(errors = listOf("boom (index.html:1)"), missing = emptyList())
        board.show(CanvasKind.SITE, "site/index.html", "site")

        assertThat(grader.verdict("notes/todo.md")).isNull()
        assertThat(asked).isEmpty()
    }

    @Test
    fun `a save under the site loads the entry page, not the saved file`() = runTest {
        answer = PageReport(errors = emptyList(), missing = emptyList())
        board.show(CanvasKind.SITE, "site/index.html", "site")

        assertThat(grader.verdict("site/style.css")).isNull()
        assertThat(asked).containsExactly("http://127.0.0.1:1/key/site/index.html")
    }

    @Test
    fun `a browser that could not look leaves the result alone`() = runTest {
        answer = null
        board.show(CanvasKind.SITE, "site/index.html", "site")

        assertThat(grader.verdict("site/index.html")).isNull()
        assertThat(asked).hasSize(1)
    }

    @Test
    fun `errors come back as a count and the lines to fix`() = runTest {
        answer = PageReport(
            errors = listOf("Uncaught ReferenceError: addItem is not defined (index.html:42)"),
            missing = listOf("app.js"),
        )
        board.show(CanvasKind.SITE, "site/index.html", "site")

        val verdict = grader.verdict("site/index.html")

        assertThat(verdict).isEqualTo(
            "The page raised 2 errors when it loaded. Fix them and save again.\n" +
                "- Uncaught ReferenceError: addItem is not defined (index.html:42)\n" +
                "- Missing file: app.js",
        )
    }

    @Test
    fun `a favicon the browser asked for on its own is not the page's error`() = runTest {
        // Measured on the phone: every WebView load asks for favicon.ico, the server says
        // 404, and a clean page was graded as missing a file it never named.
        answer = PageReport(errors = emptyList(), missing = listOf("favicon.ico"))
        board.show(CanvasKind.SITE, "site/index.html", "site")

        assertThat(grader.verdict("site/index.html")).isNull()
    }

    @Test
    fun `a missing stylesheet is reported once, not as request and complaint`() = runTest {
        // The browser refuses the 404 body as a stylesheet and says so on the console, so
        // the same missing file arrived twice with two wordings.
        answer = PageReport(
            errors = listOf(
                "Refused to apply style from 'http://127.0.0.1:1/k/site/missing.css' because " +
                    "its MIME type ('text/plain') is not a supported stylesheet MIME type (index.html:0)",
            ),
            missing = listOf("missing.css", "favicon.ico"),
        )
        board.show(CanvasKind.SITE, "site/index.html", "site")

        assertThat(grader.verdict("site/index.html")).isEqualTo(
            "The page raised 1 error when it loaded. Fix them and save again.\n" +
                "- Missing file: missing.css",
        )
    }

    @Test
    fun `a host the page reached for is named as blocked, not as a missing file`() = runTest {
        // The census's one resource failure: a stock photo from a CDN, on a page that is
        // otherwise clean. The app's rule is that nothing leaves the phone.
        answer = PageReport(
            errors = emptyList(),
            missing = listOf("favicon.ico"),
            blocked = listOf("images.unsplash.com"),
        )
        board.show(CanvasKind.SITE, "site/index.html", "site")

        assertThat(grader.verdict("site/index.html")).isEqualTo(
            "The page raised 1 error when it loaded. Fix them and save again.\n" +
                "- images.unsplash.com: nothing loads from the network; keep images and " +
                "scripts in the folder",
        )
    }

    @Test
    fun `a page saved without its closing tags is reported as cut off`() = runTest {
        // The quiz LFM2.5 stopped writing at "<div id=": the browser rendered it clean.
        answer = PageReport(errors = emptyList(), missing = emptyList())
        board.show(CanvasKind.SITE, "site/index.html", "site")

        val verdict = grader.verdict(
            "site/index.html",
            "<!DOCTYPE html><html><head><title>Quiz</title></head><body><h1>Quiz</h1><div id=",
        )

        assertThat(verdict).isEqualTo(
            "The page raised 1 error when it loaded. Fix them and save again.\n" +
                "- The file ends before </body>: it looks cut off. Save the whole page.",
        )
    }

    @Test
    fun `a whole page, a stylesheet, and a call with no content are not cut off`() = runTest {
        answer = PageReport(errors = emptyList(), missing = emptyList())
        board.show(CanvasKind.SITE, "site/index.html", "site")

        val whole = "<!DOCTYPE html><html><body><p>hi</p><script>go()</script></body></html>\n"
        assertThat(grader.verdict("site/index.html", whole)).isNull()
        assertThat(grader.verdict("site/style.css", "body { color: red }")).isNull()
        assertThat(grader.verdict("site/index.html", null)).isNull()
    }

    @Test
    fun `one error is singular and a flood is cut to what fits a prompt`() = runTest {
        answer = PageReport(errors = listOf("a (index.html:1)"), missing = emptyList())
        board.show(CanvasKind.SITE, "site/index.html", "site")
        assertThat(
            grader.verdict("site/index.html"),
        ).startsWith("The page raised 1 error when it loaded.")

        answer =
            PageReport(errors = List(20) { "error $it (index.html:$it)" }, missing = emptyList())
        val flood = grader.verdict("site/index.html")!!
        assertThat(flood).startsWith("The page raised 20 errors when it loaded.")
        assertThat(flood.lines().count { it.startsWith("- ") }).isEqualTo(CanvasGrader.MAX_LINES)
    }
}

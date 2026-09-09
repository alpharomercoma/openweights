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

import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a page did when it was loaded, as far as a browser can tell without a person.
 *
 * @param errors uncaught exceptions, unhandled rejections and anything the page itself
 *   wrote to the console at error level, in the order they happened, each with the file
 *   and line where the browser placed it.
 * @param missing resources the page named and could not get: a stylesheet or script the
 *   model forgot to save, or saved under another name.
 */
data class PageReport(
    val errors: List<String>,
    val missing: List<String>,
    /**
     * Hosts the page tried to reach and was refused, since nothing leaves the phone. The
     * census's one resource failure was a stock photo from a CDN, and calling that a
     * missing file would send the model looking for a file it never had.
     */
    val blocked: List<String> = emptyList(),
) {
    val clean: Boolean get() = lines.isEmpty()

    /**
     * What is worth telling the model, one line each.
     *
     * A file the page never named is not the page's fault: every browser asks for a
     * favicon on its own, and the server's 404 for it would otherwise be reported on every
     * clean page. And a missing stylesheet arrives twice, once as the failed request and
     * once as the console's complaint that the 404 body is not CSS; the model needs the
     * file name once. Both measured on the phone, on the first page the grader ever read.
     */
    val lines: List<String>
        get() {
            val named = missing.filterNot { it.equals(FAVICON, ignoreCase = true) }
            val complaints = errors.filterNot { error -> named.any { error.contains(it) } }
            return complaints + named.map { "Missing file: $it" } + blocked.map {
                "$it: nothing loads from the network; keep images and scripts in the folder"
            }
        }

    private companion object {
        const val FAVICON = "favicon.ico"
    }
}

/** Loads a page the way the canvas does and reports what went wrong, or null if it could not look. */
interface PageChecker {
    suspend fun check(url: String): PageReport?

    /** Never looks; for tests and for callers that have no browser. */
    object None : PageChecker {
        override suspend fun check(url: String): PageReport? = null
    }
}

/**
 * The verification loop of the canvas: the browser's errors handed back to the model.
 *
 * Until this, the canvas rendered whatever the model saved and the WebView's errors went
 * to a console nobody read. A script with a typo, a stylesheet named and never written, a
 * handler bound to an element that does not exist: the user saw a page that did nothing,
 * and the model was told "Saved". Measured on the phone over ten asks and two models
 * (`CanvasErrorCensus`), the numbers are in `docs/research/loops-and-kv-cache.md`.
 *
 * So a save under the site on screen, and the call that puts a site on screen, load the
 * page once and append what the browser said to the tool's result. Appended rather than
 * refused: the file is saved either way, and the point is for the next save to fix it,
 * which the builder rounds already leave room for. Sites only. A document or a deck goes
 * through a viewer the APK supplies, whose errors would be this project's, not the model's.
 */
@Singleton
class CanvasGrader(
    private val board: CanvasBoard,
    private val urlFor: (String) -> String,
    private val checker: PageChecker,
) {
    @Inject
    constructor(board: CanvasBoard, server: CanvasServer, checker: PageChecker) :
        this(board, server::urlFor, checker)

    /**
     * The line to add to a tool's result after [path] changed, or null when there is
     * nothing to say: no site on screen, the file is not part of it, the page is clean, or
     * the browser could not be asked.
     */
    suspend fun verdict(path: String, content: String? = null): String? {
        val canvas = board.showing.value
            ?.takeIf { it.kind == CanvasKind.SITE && it.contains(path) }
            ?: return null
        val cutOff = listOfNotNull(cutOffLine(path, content))
        val lines = cutOff + (checker.check(urlFor(canvas.entry))?.lines ?: emptyList())
        if (lines.isEmpty()) return null
        return buildString {
            append("The page raised ")
            append(lines.size)
            append(if (lines.size == 1) " error" else " errors")
            append(" when it loaded. Fix them and save again.")
            lines.take(MAX_LINES).forEach { append("\n- ").append(it.take(MAX_LINE_CHARS)) }
        }
    }

    /**
     * A page that stops before its closing tags was cut, not finished.
     *
     * The browser cannot say so: HTML forgives a missing `</body>` and renders what is
     * there, so a page the model stopped writing halfway through parses without a word.
     * The census found one of those (the quiz LFM2.5 wrote stopped at `<div id=`, 259
     * characters in) and a browser reported it clean. The save itself knows.
     */
    private fun cutOffLine(path: String, content: String?): String? {
        if (content == null || !path.endsWith(".html", ignoreCase = true)) return null
        val tail = content.trimEnd().takeLast(CLOSING_WINDOW).lowercase()
        if ("</html>" in tail || "</body>" in tail) return null
        return "The file ends before </body>: it looks cut off. Save the whole page."
    }

    companion object {
        /** Enough to act on and small enough to keep in a prompt; the rest repeats. */
        const val MAX_LINES = 4

        /** How far from the end the closing tags may sit, past a trailing script or two. */
        const val CLOSING_WINDOW = 400
        const val MAX_LINE_CHARS = 200

        /** A grader that never has anything to say; for tests and fixtures. */
        fun none(): CanvasGrader = CanvasGrader(CanvasBoard(), { it }, PageChecker.None)
    }
}

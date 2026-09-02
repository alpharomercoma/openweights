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
import org.junit.Test

/**
 * The canvas's contract with the write tool: which saves repaint the screen.
 */
class CanvasBoardTest {

    @Test
    fun `a save under the shown folder bumps the revision`() {
        val board = CanvasBoard()
        board.show(CanvasKind.SITE, "site/index.html", "site")

        board.changed("site/style.css")

        assertThat(board.showing.value?.revision).isEqualTo(1)
    }

    @Test
    fun `a save elsewhere does not repaint`() {
        val board = CanvasBoard()
        board.show(CanvasKind.SITE, "site/index.html", "site")

        board.changed("notes/todo.md")
        // A sibling whose name merely begins the same is elsewhere too.
        board.changed("site-notes/plan.md")

        assertThat(board.showing.value?.revision).isEqualTo(0)
    }

    @Test
    fun `a document at the root repaints on its own save`() {
        val board = CanvasBoard()
        board.show(CanvasKind.DOCUMENT, "report.md", "")

        board.changed("report.md")

        assertThat(board.showing.value?.revision).isEqualTo(1)
    }

    @Test
    fun `a site owns its folder and a document owns only itself`() {
        // What the server is allowed to hand a page. A site reads its own stylesheet and
        // nothing above its folder; a document rendered by the bundled viewer needs the one
        // file and must not be able to read the notes beside it.
        val site = Canvas(CanvasKind.SITE, "site/index.html", "site")
        assertThat(site.contains("site/style.css")).isTrue()
        assertThat(site.contains("site/index.html")).isTrue()
        assertThat(site.contains("notes/passwords.md")).isFalse()
        assertThat(site.contains("site-notes/plan.md")).isFalse()

        val document = Canvas(CanvasKind.DOCUMENT, "report.md", "")
        assertThat(document.contains("report.md")).isTrue()
        assertThat(document.contains("notes/passwords.md")).isFalse()

        val deck = Canvas(CanvasKind.SLIDES, "talk/slides.md", "talk")
        assertThat(deck.contains("talk/slides.md")).isTrue()
        assertThat(deck.contains("talk/notes.md")).isFalse()
    }

    @Test
    fun `showing again is a new generation, editing is not`() {
        val board = CanvasBoard()
        board.show(CanvasKind.SITE, "site/index.html", "site")
        val first = board.showing.value?.generation

        board.changed("site/index.html")
        assertThat(board.showing.value?.generation).isEqualTo(first)

        board.show(CanvasKind.SITE, "site/about.html", "site")
        assertThat(board.showing.value?.generation).isEqualTo(first!! + 1)
    }
}

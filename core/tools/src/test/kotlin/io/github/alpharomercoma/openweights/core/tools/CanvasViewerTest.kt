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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The promises the two viewers make about layout, pinned in the files that make them.
 *
 * Neither promise can be proved here: a page only overflows once a browser has laid it
 * out, and Robolectric's WebView runs no JavaScript. What can be pinned is that the rules
 * the promises rest on are still in the files, because both were arrived at by measuring
 * and each is one deletion away from silently going.
 *
 * How they were measured, and how to measure them again after touching either file: serve
 * `core/tools/src/main/assets/canvas` with the `__OW_*` placeholders filled in, load
 * `deck.html` and `doc.html` in a real browser, and read `window.__ow` — the deck reports
 * `fits`, one scale per slide, and the document reports `pages` and `pageSize`. Then walk
 * every element and compare its right edge against its page's. On 2026-09-05 that walk
 * found a bare URL hanging 73pt off the A4 box, which is the rule added below.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasViewerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun asset(name: String): String =
        context.assets.open("canvas/$name").use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `a document breaks anything too long for the page rather than hanging off it`() {
        val css = asset("doc-paged.css")

        // A page is a fixed width and its content is a model's Markdown. `pre` and `table`
        // said this for themselves; prose and links did not, and a long URL is the
        // commonest long token anything writes.
        assertThat(css).contains("overflow-wrap: anywhere")
        val links = css.lines().first { it.trimStart().startsWith("a {") }
        assertWithMessage("a bare URL sets the paragraph's minimum width without this")
            .that(links).contains("overflow-wrap")
        // The rules that were already right, kept from being lost to a tidy-up.
        assertThat(css).contains("white-space: pre-wrap")
        assertThat(css).contains("table-layout: fixed")
        assertThat(css).contains("img { max-width: 100%; }")
    }

    @Test
    fun `a deck scales a slide that does not fit instead of clipping it`() {
        val deck = asset("deck.html")

        // The stage is a fixed 1280x720 because that is what a projector expects, so a
        // slide with too much on it has exactly two options and only one of them keeps
        // the words on screen.
        assertThat(deck).contains("function fit(slide)")
        assertThat(deck).contains("scale(")
        assertThat(deck).contains("overflow: hidden")
        // Refitted when the window changes, or a deck opened in one orientation is wrong
        // in the other for as long as it stays open.
        assertThat(deck).contains("ResizeObserver")
    }

    @Test
    fun `both viewers report what they laid out, so the promise can be checked`() {
        // The hook the browser walk above reads. Without it there is no way to ask a
        // rendered deck whether anything had to be shrunk, or a document how many pages
        // it became, and both claims go back to being assertions in a comment.
        assertThat(asset("deck.html")).contains("window.__ow")
        assertThat(asset("deck.html")).contains("fits")
        assertThat(asset("doc.html")).contains("window.__ow")
        assertThat(asset("doc.html")).contains("pages:")
    }

    @Test
    fun `a page that fails to paginate still shows its text`() {
        // Paged.js throwing must not mean an empty screen: the fallback renders the
        // Markdown as one long page and says so in the hook.
        val doc = asset("doc.html")

        assertThat(doc).contains("unpaged")
        assertThat(doc).contains("pagination failed")
    }
}

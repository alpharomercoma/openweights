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
import kotlin.system.measureTimeMillis

/**
 * Searching a fetched page for what was actually wanted.
 *
 * The pattern is written by a language model, so the two things that matter here are that
 * an ordinary one finds the answer wherever it sits, and that a bad one cannot take the
 * turn down with it: `java.util.regex` backtracks, has no timeout, and a page is up to
 * half a megabyte of somebody else's text.
 */
class PageSearchTest {

    private fun page(needle: String, at: Int, length: Int = 20_000): String = buildString {
        append("filler ".repeat(at / 7))
        append(needle)
        while (this.length < length) append(" more words here")
    }

    @Test
    fun `a match deep in the page comes back, which the opening never did`() {
        // The whole reason this exists: the excerpt is the first four thousand characters
        // and the answer is at fifteen thousand.
        val text = page("The battery is 5000 mAh.", at = 15_000)

        val result = PageSearch.search(text, "battery is [0-9]+ mAh")

        assertThat(result).isInstanceOf(PageSearch.Result.Found::class.java)
        val found = result as PageSearch.Result.Found
        assertThat(found.count).isEqualTo(1)
        assertThat(found.windows.single()).contains("5000 mAh")
    }

    @Test
    fun `the match arrives with the text around it, not on its own`() {
        val text = "Nothing here. The release date is 5 September 2026. Nothing after."

        val found = PageSearch.search(text, "release date") as PageSearch.Result.Found

        // A bare match is unreadable; what makes it an answer is the sentence it sits in.
        assertThat(found.windows.single()).contains("5 September 2026")
    }

    @Test
    fun `a pattern that is not a regular expression is searched for as text`() {
        // What a model writes when asked to find C++, and a syntax error to the compiler.
        val text = "This library is written in C++ and Rust."

        val found = PageSearch.search(text, "C++") as PageSearch.Result.Found

        assertThat(found.count).isEqualTo(1)
        assertThat(found.windows.single()).contains("C++")
    }

    @Test
    fun `case is ignored, because nobody means the capitals in a find box`() {
        val found = PageSearch.search("The Specification says so.", "SPECIFICATION")

        assertThat(found).isInstanceOf(PageSearch.Result.Found::class.java)
    }

    @Test
    fun `a page that does not match says so as a fact about the page`() {
        val text = "A page about gardening."

        val result = PageSearch.search(text, "quarterly revenue")

        assertThat(result).isEqualTo(PageSearch.Result.Absent)
        val said = PageSearch.render(result, "quarterly revenue", text.length)
        // The length is in there so the model can choose between a looser pattern and
        // reading the page whole, rather than reporting that the page does not mention it.
        assertThat(said).contains("${text.length} characters")
        assertThat(said).contains("without find")
    }

    @Test
    fun `a catastrophic pattern is abandoned rather than running forever`() {
        // The classic: nested quantifiers against a long run that cannot match. Left to
        // itself this does not finish, and there is no timeout in java.util.regex.
        val text = "a".repeat(6_000) + "c"

        var result: PageSearch.Result
        val elapsed = measureTimeMillis {
            result = PageSearch.search(text, "(a+)+b")
        }

        assertThat(result).isEqualTo(PageSearch.Result.TooSlow)
        // Generously above the 750 ms budget, because a loaded CI box is slow and the
        // claim under test is "bounded", not "fast".
        assertThat(elapsed).isLessThan(15_000)
    }

    @Test
    fun `a pattern that matches the empty string terminates`() {
        // "x*" matches at every position, including between characters, so a naive loop
        // never advances.
        val result = PageSearch.search("abc", "x*")

        assertThat(result).isInstanceOf(PageSearch.Result.Found::class.java)
    }

    @Test
    fun `neighbouring matches are joined rather than repeated`() {
        // Two matches four characters apart have almost the same three hundred characters
        // around them; sent separately that is the same text twice.
        val text = "start " + "word ".repeat(4) + "target one target two " +
            "word ".repeat(400)

        val found = PageSearch.search(text, "target") as PageSearch.Result.Found

        assertThat(found.count).isEqualTo(2)
        assertThat(found.windows).hasSize(1)
    }

    @Test
    fun `a pattern matching everything is capped rather than returned whole`() {
        val text = "word ".repeat(5_000)

        val found = PageSearch.search(text, "word") as PageSearch.Result.Found
        val said = PageSearch.render(found, "word", text.length)

        // Capped in both directions: the number of matches walked, and the characters
        // returned, because either one unbounded is the whole page again.
        assertThat(found.count).isAtMost(12)
        assertThat(found.more).isTrue()
        assertThat(said.length).isLessThan(5_000)
        assertThat(said).contains("there may be more")
    }

    @Test
    fun `what comes back says how many places matched`() {
        val text = "alpha beta alpha gamma alpha"

        val said = PageSearch.render(PageSearch.search(text, "alpha"), "alpha", text.length)

        // Three matches, merged into one readable passage. The header counts the matches,
        // because "1 of 3" read as two of them having been thrown away.
        assertThat(said).contains("3 places")
        assertThat(said).doesNotContain("there may be more")
    }
}

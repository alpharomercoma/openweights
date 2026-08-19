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
 * What survives a page, and what does not.
 *
 * The tool returns four thousand characters and a phone decodes them one at a time, so what
 * those characters are spent on is the whole question. A page that spends them on a cookie
 * notice and a menu produces a model that answers out of the furniture, or reports that a page
 * does not mention what it plainly does.
 *
 * Nothing here touches the network or the platform: [withoutFurniture] is the half of the
 * cleanup that is string work, split from the entity decoding for exactly this reason.
 */
class PageFurnitureTest {
    @Test
    fun `an article is taken out of the page around it`() {
        val page = """
            <html><body>
              <nav>Home Products Pricing About Contact</nav>
              <div class="cookie">We value your privacy. Accept all cookies?</div>
              <article>$PROSE</article>
              <aside>Related: five things you missed</aside>
            </body></html>
        """.trimIndent()

        val read = page.withoutFurniture()

        assertThat(read).contains("Ada Lovelace")
        assertThat(read).doesNotContain("value your privacy")
        assertThat(read).doesNotContain("Pricing")
    }

    @Test
    fun `main is used when there is no article`() {
        val page = "<body><nav>Menu</nav><main>$PROSE</main><footer>Copyright</footer></body>"

        val read = page.withoutFurniture()

        assertThat(read).contains("Ada Lovelace")
        assertThat(read).doesNotContain("Copyright")
    }

    @Test
    fun `a page naming neither is still read, just cleaned`() {
        // The fallback carries most of the web. Landmarks are a gift when they are there and
        // must not be a requirement, or a page without them returns nothing at all.
        val page = "<body><script>var x = 1</script><div>$PROSE</div></body>"

        val read = page.withoutFurniture()

        assertThat(read).contains("Ada Lovelace")
        assertThat(read).doesNotContain("var x")
    }

    @Test
    fun `the largest article wins, so an index page yields its biggest card`() {
        val page = """
            <body>
              <article>Short teaser one.</article>
              <article>$PROSE</article>
              <article>Short teaser two.</article>
            </body>
        """.trimIndent()

        val read = page.withoutFurniture()

        assertThat(read).contains("Ada Lovelace")
    }

    @Test
    fun `a teaser too small to be a body falls through to the whole page`() {
        // An index page of headlines has articles, and every one of them is a card. Taking the
        // largest would return one headline and call it the page. Below the threshold the
        // fuller text is the better answer, because it at least contains all of them.
        val page = "<body><article>One headline.</article><div>$PROSE</div></body>"

        val read = page.withoutFurniture()

        assertThat(read).contains("Ada Lovelace")
        assertThat(read).contains("One headline")
    }

    @Test
    fun `script inside the article goes, and the prose around it stays`() {
        // Analytics and JSON-LD live inside the content on plenty of sites, and a model given
        // a page of tracking snippets reads them as though somebody wrote them.
        val page = "<article>Before. <script>gtag('event')</script> After. $PROSE</article>"

        val read = page.withoutFurniture()

        assertThat(read).contains("Before.")
        assertThat(read).contains("After.")
        assertThat(read).doesNotContain("gtag")
    }

    @Test
    fun `a form is furniture, including the search box on every page`() {
        val page = "<main>$PROSE<form><input placeholder='Search'><button>Go</button></form></main>"

        val read = page.withoutFurniture()

        assertThat(read).contains("Ada Lovelace")
        assertThat(read).doesNotContain("Search")
    }

    private companion object {
        /**
         * Long enough to clear the threshold that separates a body from a teaser, and dull
         * enough that nothing in it can be mistaken for the furniture around it.
         */
        val PROSE = "Ada Lovelace worked with Charles Babbage on the Analytical Engine. "
            .repeat(10)
    }
}

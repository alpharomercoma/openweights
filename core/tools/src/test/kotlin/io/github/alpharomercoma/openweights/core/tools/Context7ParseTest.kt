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
 * Reading Context7, against what Context7 actually sent.
 *
 * Both fixtures are real responses, saved rather than written, because the interesting
 * behaviour is one nobody would think to invent: asked something that has nothing to do with
 * code, the service answers anyway, at length, and confidently. Thirty results for "what is
 * the weather in Manila right now", the best of them an Android sample app and two libraries
 * whose names begin with "what". Their scores sit in the same range as a real match, so the
 * score cannot be what separates them.
 *
 * The filter below throws out the confident nonsense, and what survives the weather question
 * is a library genuinely called Weather. That is not a filter bug, it is a correct match to a
 * question that was not about code, and it is why this source is off unless the user asks for
 * it rather than being a fallback everyone gets.
 */
class Context7ParseTest {
    private companion object {
        /**
         * How much of thirty results survives a question that was not about code.
         *
         * One, at the time of recording, and it is a library called Weather. Written as a
         * ceiling rather than an equality because the index changes and this is a bound on
         * the damage, not a claim about Context7's catalogue.
         */
        const val SURVIVORS = 2
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `a library question finds the library`() {
        val hits = parseContext7(fixture("context7-library.json"), "kotlin coroutines", limit = 3)

        assertThat(hits).isNotNull()
        assertThat(hits!!).isNotEmpty()
        assertThat(hits.first().title).contains("Coroutines")
        assertThat(hits.first().url).startsWith("https://context7.com/")
        assertThat(hits.first().snippet).isNotEmpty()
    }

    @Test
    fun `a question about the weather keeps almost nothing, and that is the finding`() {
        // Thirty results in, and the filter throws out the confident nonsense: Now in
        // Android, what-the-fetch, What The Patch, PHP The Right Way, and a testing
        // mini-framework literally called Is. Each of those was a separate leak found by
        // running this fixture rather than by imagining one.
        //
        // What survives is a library genuinely called Weather, which is a correct match to a
        // question that was not about code. No filter fixes that, and this test does not
        // pretend otherwise: it is the reason documentation search is off unless the user
        // asks for it, because the chain stops at the first source that answers and a wrong
        // answer here costs the web its turn.
        val hits = parseContext7(
            fixture("context7-unrelated.json"),
            "what is the weather in Manila right now",
            limit = 30,
        )

        assertThat(hits!!.map { it.title })
            .containsNoneOf("Now in Android", "what-the-fetch", "What The Patch", "Is")
        assertThat(hits.size).isAtMost(SURVIVORS)
    }

    @Test
    fun `the filter is the words of the question, not the score`() {
        assertThat(looksLikeAMatch("kotlin coroutines", "Kotlinx Coroutines")).isTrue()
        assertThat(looksLikeAMatch("react hooks", "React")).isTrue()
        // The three that the real response ranked highest for a weather question.
        assertThat(looksLikeAMatch("what is the weather in Manila right now", "Now in Android"))
            .isFalse()
        assertThat(looksLikeAMatch("what is the weather in Manila right now", "what-the-fetch"))
            .isFalse()
        assertThat(looksLikeAMatch("what is the weather in Manila right now", "What The Patch"))
            .isFalse()
    }

    @Test
    fun `a body that is not the shape expected is a failure rather than an empty answer`() {
        // Null and empty mean different things to the chain: empty is "nothing matched here",
        // null is "this provider could not answer", and only the second should look like a
        // reason to try somewhere else.
        assertThat(parseContext7("not json at all", "kotlin", limit = 3)).isNull()
        assertThat(parseContext7("""{"error":"nope"}""", "kotlin", limit = 3)).isNull()
    }

    @Test
    fun `no more than asked for comes back`() {
        val hits = parseContext7(fixture("context7-library.json"), "kotlin", limit = 2)

        assertThat(hits!!.size).isAtMost(2)
    }
}

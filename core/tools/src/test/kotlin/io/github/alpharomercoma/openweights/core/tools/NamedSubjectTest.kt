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
 * The questions here are the ones the 2026-09-05 host probe was run on, plus the routing
 * matrix's own rows. What is pinned is the split: a name is looked up, settled knowledge is
 * not, and a pronoun is not a name.
 */
class NamedSubjectTest {
    @Test
    fun `a character nobody at this size can recall is a subject`() {
        assertThat(NamedSubject.of("Who is Killua?")).isEqualTo("Killua")
        assertThat(NamedSubject.of("Who is Gon Freecss?")).isEqualTo("Gon Freecss")
        assertThat(NamedSubject.of("who is gon freeks")).isEqualTo("gon freeks")
        assertThat(NamedSubject.of("Who is Yor Forger?")).isEqualTo("Yor Forger")
    }

    @Test
    fun `a famous person is a subject too, and the search is the accepted price`() {
        assertThat(NamedSubject.of("Who is Albert Einstein?")).isEqualTo("Albert Einstein")
        assertThat(NamedSubject.of("Who was Ada Lovelace?")).isEqualTo("Ada Lovelace")
    }

    @Test
    fun `the other three shapes`() {
        assertThat(NamedSubject.of("What happens at the end of Attack on Titan?"))
            .isEqualTo("Attack on Titan")
        assertThat(NamedSubject.of("Tell me about the Riverlight Festival in Dagupan."))
            .isEqualTo("Riverlight Festival in Dagupan")
        assertThat(NamedSubject.of("What does the company Veltrix Labs do?")).isNull()
        assertThat(NamedSubject.of("What is the Quenlark 7?")).isEqualTo("Quenlark 7")
        assertThat(NamedSubject.of("What is JEPA-3?")).isNull()
    }

    @Test
    fun `settled knowledge is not a subject`() {
        listOf(
            "What is the capital of France?",
            "What is 2+2?",
            "What is photosynthesis?",
            "Explain photosynthesis briefly.",
            "Who wrote Pride and Prejudice?",
            "Translate 'good morning' into Spanish.",
            "Write a haiku about rain.",
            "What is an iPhone?",
            "What is today's date?",
        ).forEach { assertThat(NamedSubject.of(it)).isNull() }
    }

    @Test
    fun `a pronoun is not a name`() {
        listOf("Who is she?", "who is it", "Who are they?", "Who is this?", "Who am I")
            .forEach { assertThat(NamedSubject.of(it)).isNull() }
    }

    @Test
    fun `a paragraph that happens to open with who is is left alone`() {
        val pasted = "Who is " + "x".repeat(200) + "?"
        assertThat(NamedSubject.of(pasted)).isNull()
    }

    @Test
    fun `the trailer names the subject and the tool`() {
        val trailer = NamedSubject.trailer("Killua")

        assertThat(trailer).contains("names Killua")
        assertThat(trailer).contains(WebSearchTool.NAME)
        assertThat(trailer).startsWith("(")
        assertThat(trailer).endsWith(")")
    }
}

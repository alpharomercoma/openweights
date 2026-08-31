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

package io.github.alpharomercoma.openweights.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The floor under a model that has stopped saying anything new.
 *
 * Most of what is here is negative, and deliberately so. This guard cuts a reply off, so a
 * false positive destroys work the user asked for while a false negative costs seconds; the
 * cases that must never trip are therefore worth more tests than the case that must.
 */
class DegenerationTest {
    @Test
    fun `a sentence written over and over is caught`() {
        val looping = "I cannot help with that request right now.\n".repeat(40)

        assertThat(Degeneration.dominates(looping)).isTrue()
    }

    @Test
    fun `a repeat that ignores the line breaks is caught too`() {
        // The same fragment run together without newlines, which is what a model that has
        // lost the end-of-line token produces and what the line check alone would miss.
        val looping = "the answer is forty two and the answer is forty two ".repeat(30)

        assertThat(Degeneration.dominates(looping)).isTrue()
    }

    @Test
    fun `a legitimately repetitive answer is delivered before the guard looks at it`() {
        // Fifty blank checklist rows is a thing people ask for, and it is exactly periodic:
        // by the mechanism alone it is indistinguishable from a loop. What separates them
        // here is that the guard does not engage until far more has been written than a
        // request like this produces, which is why the threshold is set where it is rather
        // than at the smallest value that works.
        val rows = "- [ ] \n".repeat(50)

        assertThat(rows.length).isLessThan(Degeneration.MIN_CHARS)
        assertThat(Degeneration.dominates(rows)).isFalse()
    }

    @Test
    fun `a short reply is never cut`() {
        // Too short to judge. A sentence stopped mid-word can trivially repeat a phrase and
        // continuing it is legitimate, so the guard stays out of it.
        val short = "no no no no no no no no no no"

        assertThat(Degeneration.dominates(short)).isFalse()
    }

    @Test
    fun `an ordinary long answer is left alone`() {
        val prose = buildString {
            repeat(30) { index ->
                append("Paragraph $index explains a different part of the problem ")
                append("and introduces vocabulary the previous one did not use.\n\n")
            }
        }

        assertThat(Degeneration.dominates(prose)).isFalse()
    }

    @Test
    fun `a templated list sharing a long clause is not repetition`() {
        // The false positive that sank the first version of this guard. Every item repeats
        // the same sixty-character clause, so by "does one fragment cover half the reply"
        // this is degenerate; by "did it say the same thing twice in a row" it plainly is
        // not, and it is an ordinary thing to ask a model for.
        val list = buildString {
            repeat(30) { index ->
                append("Paragraph $index explains a different part of the problem ")
                append("and introduces vocabulary the previous one did not use.\n\n")
            }
        }

        assertThat(Degeneration.dominates(list)).isFalse()
    }

    @Test
    fun `a good answer that then starts looping is still caught`() {
        // The reason the tail is what gets measured. Three useful paragraphs followed by a
        // loop is a loop, and a measure taken over the whole reply would be diluted by the
        // good part until the phone had spent the rest of the budget on the bad one.
        val answer = buildString {
            repeat(3) { index ->
                appendLine("A real paragraph number $index, saying something particular.")
            }
            repeat(40) { appendLine("Let me know if you need anything else!") }
        }

        assertThat(Degeneration.dominates(answer)).isTrue()
    }

    @Test
    fun `a markdown table is not repetition`() {
        // Rows share a shape and a leading pipe, which is exactly what a naive check would
        // read as an echo. They are different rows and this is the commonest long answer
        // the app renders.
        val table = buildString {
            appendLine("| City | Country | Population |")
            appendLine("| --- | --- | --- |")
            listOf(
                "Manila" to "Philippines",
                "Jakarta" to "Indonesia",
                "Bangkok" to "Thailand",
                "Hanoi" to "Vietnam",
                "Seoul" to "South Korea",
                "Tokyo" to "Japan",
                "Taipei" to "Taiwan",
                "Singapore" to "Singapore",
            ).forEachIndexed { index, (city, country) ->
                appendLine("| $city | $country | ${1_000_000 + index * 137_000} |")
            }
        }

        assertThat(Degeneration.dominates(table)).isFalse()
    }

    @Test
    fun `a numbered list is not repetition`() {
        val list = buildString {
            repeat(25) { index ->
                appendLine("${index + 1}. Step ${index + 1}: do the thing that step needs.")
            }
        }

        assertThat(Degeneration.dominates(list)).isFalse()
    }

    @Test
    fun `code with similar looking lines is not repetition`() {
        val code = buildString {
            appendLine("```kotlin")
            repeat(20) { index ->
                appendLine("    val field$index = source.readField(\"name$index\")")
            }
            appendLine("```")
        }

        assertThat(Degeneration.dominates(code)).isFalse()
    }

    @Test
    fun `one repeated heading in a real answer is not enough`() {
        // A repeat has to cover most of the reply, not merely appear in it. Four identical
        // separators inside a long answer are formatting, not a loop.
        val answer = buildString {
            repeat(4) { index ->
                appendLine("---")
                appendLine("Section $index covers material the other sections do not, ")
                appendLine("at enough length to be the substance of the reply rather than ")
                appendLine("an aside, which is the point being tested here.")
            }
        }

        assertThat(Degeneration.dominates(answer)).isFalse()
    }

    @Test
    fun `a whole answer repeated is caught after the third copy`() {
        // The SmolLM2 failure as observed: not one word in a rut but the entire answer,
        // a block of a few hundred characters, emitted again and again. A window of 800
        // over four runs could never see a period this long, so the loop ran the whole
        // budget with the guard watching.
        val block = buildString {
            append("The capital of France is Paris. Paris has been the capital since ")
            append("the tenth century and is home to over two million people within the ")
            append("city limits. It is known for the Eiffel Tower, the Louvre, and its ")
            append("many cafes along the Seine. Is there anything else you would like ")
            append("to know about it today? ")
        }
        val looping = "Here is your answer. " + block.repeat(9)

        assertThat(Degeneration.dominates(looping)).isTrue()
    }

    @Test
    fun `long varied prose never trips the wider window`() {
        val prose = buildString {
            repeat(60) { index ->
                appendLine(
                    "Paragraph $index considers a slightly different aspect of the " +
                        "question, citing figure ${index * 7} and drawing its own conclusion.",
                )
            }
        }

        assertThat(Degeneration.dominates(prose)).isFalse()
    }
}

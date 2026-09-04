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

package io.github.alpharomercoma.openweights.core.common.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.ln

/**
 * The arithmetic behind the uncertainty view, which has to be right before anything is drawn.
 *
 * Perplexity is a definition, not a heuristic: `exp(-(1/N) * sum(ln p))`. A screen that says
 * "perplexity 3.4" is making a claim other people can check against `llama-perplexity`, so
 * the number is pinned here against hand arithmetic rather than against whatever the code
 * happened to produce.
 */
class ConfidenceTest {
    /** ln p for a probability, which is the unit the engine reports. */
    private fun logOf(probability: Double) = ln(probability).toFloat()

    @Test
    fun `a model that was certain of everything has a perplexity of one`() {
        val confidence = ReplyConfidence.of(
            texts = listOf("The", " sky", " is", " blue"),
            logprobs = List(4) { 0f },
        )

        assertThat(confidence.perplexity).isWithin(1e-6).of(1.0)
        assertThat(confidence.uncertainRuns).isEmpty()
    }

    @Test
    fun `perplexity is the geometric mean of the probabilities, inverted`() {
        // Four tokens at one half each: the effective branching factor is two, which is the
        // whole intuition behind the number and the thing a wrong implementation loses.
        val confidence = ReplyConfidence.of(
            texts = listOf("a", "b", "c", "d"),
            logprobs = List(4) { logOf(0.5) },
        )

        assertThat(confidence.perplexity).isWithin(1e-5).of(2.0)
        assertThat(confidence.tokenCount).isEqualTo(4)
    }

    @Test
    fun `one hesitation in a fluent answer barely moves the average and is still found`() {
        // The case the whole design turns on. Nine tokens at 0.99 and one at 0.03 is the
        // shape of a fluent paragraph with an invented number in it: the mean is still
        // comfortable and the minimum is not, which is why both are reported.
        val texts = List(9) { "word" } + "1847"
        val logprobs = List(9) { logOf(0.99) } + logOf(0.03)

        val confidence = ReplyConfidence.of(texts, logprobs)

        assertThat(confidence.perplexity).isLessThan(1.5)
        assertThat(confidence.leastProbable).isWithin(1e-6).of(0.03)
        assertThat(confidence.uncertainRuns.map { it.text }).containsExactly("1847")
    }

    @Test
    fun `neighbouring tokens on the same side of the threshold become one run`() {
        // A token is a unit of arithmetic, not of reading. Underlining "Kra", "k" and "ów"
        // separately would be saying something about the tokenizer.
        val confidence = ReplyConfidence.of(
            texts = listOf("Kra", "k", "ów", " is", " lovely"),
            logprobs = listOf(logOf(0.05), logOf(0.04), logOf(0.9), 0f, 0f),
        )

        val uncertain = confidence.uncertainRuns.single()
        assertThat(uncertain.text).isEqualTo("Krak")
        // The run keeps the lowest probability in it, so a hesitation cannot be averaged
        // away by the confident token beside it.
        assertThat(uncertain.probability).isWithin(1e-6).of(0.04)
    }

    @Test
    fun `the runs put back together are the text that was measured`() {
        val texts = listOf("Two", " plus", " two", " is", " five")
        val confidence = ReplyConfidence.of(
            texts = texts,
            logprobs = listOf(0f, 0f, 0f, 0f, logOf(0.01)),
        )

        assertThat(confidence.runs.joinToString("") { it.text }).isEqualTo(texts.joinToString(""))
    }

    @Test
    fun `only the answer is measured, not the thinking that came before it`() {
        // The reasoning block is not what the user is being asked to trust, and a model
        // deliberating is exactly where the low probabilities are. Averaging them into a
        // number labelled as being about the answer would make every thinking model look
        // unsure of everything it said.
        val confidence = ReplyConfidence.of(
            texts = listOf("<think>", "maybe", "</think>", "Paris", "."),
            logprobs = listOf(0f, logOf(0.01), 0f, 0f, 0f),
            answer = "Paris.",
        )

        assertThat(confidence.tokenCount).isEqualTo(2)
        assertThat(confidence.perplexity).isWithin(1e-6).of(1.0)
        assertThat(confidence.uncertainRuns).isEmpty()
    }

    @Test
    fun `an answer that cannot be found in the reply measures the whole reply`() {
        // The parser trims and rewrites. Measuring nothing at all would be worse than
        // measuring a little too much, because nothing is indistinguishable from a model
        // that was certain.
        val confidence = ReplyConfidence.of(
            texts = listOf("a", "b"),
            logprobs = listOf(logOf(0.5), logOf(0.5)),
            answer = "something else entirely",
        )

        assertThat(confidence.tokenCount).isEqualTo(2)
    }

    @Test
    fun `nothing measured is not the same as a model that was sure`() {
        assertThat(ReplyConfidence.of(emptyList(), emptyList())).isEqualTo(ReplyConfidence.NONE)
        assertThat(ReplyConfidence.NONE.perplexity).isEqualTo(0.0)
        // Mismatched lengths are a bug somewhere upstream, and inventing a number for them
        // would be the bug reaching the screen.
        assertThat(ReplyConfidence.of(listOf("a"), listOf(0f, 0f))).isEqualTo(ReplyConfidence.NONE)
    }

    @Test
    fun `the threshold in probability and in log probability are the same threshold`() {
        assertThat(ReplyConfidence.UNCERTAIN_LOGPROB).isWithin(1e-9)
            .of(ln(ReplyConfidence.UNCERTAIN_BELOW))
    }
}

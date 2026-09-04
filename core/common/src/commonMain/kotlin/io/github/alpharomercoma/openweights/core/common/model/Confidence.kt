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

import kotlin.math.exp
import kotlin.math.ln

/**
 * One run of text the model wrote, and how sure it was of it.
 *
 * A run rather than a token: adjacent tokens that fall on the same side of the threshold
 * are merged before anything is drawn, because a token is a unit of arithmetic and not a
 * unit of reading. "Kraków" can be four tokens and underlining three of them separately
 * says something about the tokenizer rather than about the model.
 */
data class ConfidenceRun(val text: String, val probability: Double, val uncertain: Boolean)

/**
 * What one reply's per-token probabilities came to.
 *
 * ### What perplexity is
 *
 * `exp(-(1/N) * sum(ln p))` over the tokens of the answer: the standard definition, the one
 * `llama-perplexity` computes and the one every paper means by the word. It reads as an
 * effective branching factor. One means the model had no doubt at any point; ten means it
 * was choosing, on average, as though from ten equally good options.
 *
 * ### What it is not
 *
 * It is not a truth score, and the honest way to ship it is to say so on the screen it
 * appears on. A model states a wrong date with the same serene confidence it states a right
 * one, because confidence is about how well the sentence matches the training distribution
 * and not about the world. What it does measure is where the model hesitated, which is a
 * real and different thing: a hesitation is where an answer is worth checking, and a
 * paragraph with no hesitation in it is either right or confidently wrong.
 *
 * The average is also dominated by the easy tokens. Punctuation, "the", the second half of
 * a word already begun: these run at probabilities near one and there are a lot of them, so
 * a long fluent answer with one invented number in it has a low perplexity. That is why
 * [uncertainRuns] is carried beside [perplexity] rather than folded into it, and why the
 * screen leads with the places rather than with the number.
 */
data class ReplyConfidence(
    val runs: List<ConfidenceRun>,
    val perplexity: Double,
    /** How many tokens the perplexity averages over. Zero means there is nothing to say. */
    val tokenCount: Int,
) {
    val uncertainRuns: List<ConfidenceRun> get() = runs.filter { it.uncertain }

    /**
     * The least sure the model was anywhere in the answer.
     *
     * Carried because it is the number the literature finds actually correlates with an
     * answer being wrong, where the mean does not: one invented token in a fluent paragraph
     * moves the minimum and barely moves the average.
     */
    val leastProbable: Double? get() = runs.minOfOrNull { it.probability }

    companion object {
        /**
         * Nothing measured, which is not the same as a model that was certain.
         *
         * A perplexity of one would be the second thing, and printing it for the first is
         * the kind of plausible number this whole feature exists to stop being shown.
         */
        val NONE = ReplyConfidence(emptyList(), perplexity = 0.0, tokenCount = 0)

        /**
         * Below this probability a token is drawn as uncertain.
         *
         * One in five, which is a stronger claim than it sounds: a token at 0.2 was the
         * model's choice among alternatives it rated nearly as good, and in ordinary prose
         * that is rare. The overwhelming majority of tokens in a fluent answer sit above
         * 0.9, so a threshold this low marks the genuine forks and not the grammar.
         *
         * A constant rather than a slider, until it has been calibrated against a model on
         * a phone: `ConfidenceProbe` is that measurement, and a knob offered before it
         * would be a number the user has no more basis to choose than the app does.
         */
        const val UNCERTAIN_BELOW = 0.20

        /** The same threshold in the units the engine reports. */
        val UNCERTAIN_LOGPROB: Double = ln(UNCERTAIN_BELOW)

        /**
         * Folds a reply's tokens into runs and a perplexity.
         *
         * @param texts each token's text, in order, concatenating to the raw reply.
         * @param logprobs the natural log of the probability the model gave each of them.
         * @param answer the visible part of the reply, or null to measure the whole thing.
         *
         * Restricted to [answer] when one is given, because the reasoning block is not what
         * the user is being asked to trust and its tokens would be averaged into a number
         * presented as being about the answer. Located by search rather than by index: the
         * parser trims and rewrites, so the only reliable question is where the answer's
         * text sits inside the raw reply, and a reply whose answer cannot be found there
         * measures the whole reply rather than silently measuring nothing.
         */
        fun of(
            texts: List<String>,
            logprobs: List<Float>,
            answer: String? = null,
        ): ReplyConfidence {
            if (texts.isEmpty() || texts.size != logprobs.size) return NONE

            val raw = texts.joinToString("")
            val from = answer?.takeIf { it.isNotEmpty() }?.let { raw.indexOf(it) } ?: -1
            val until = if (from >= 0) from + answer!!.length else raw.length
            val start = if (from >= 0) from else 0

            var cursor = 0
            val kept = mutableListOf<Pair<String, Float>>()
            for (index in texts.indices) {
                val end = cursor + texts[index].length
                // Any overlap at all, so a token straddling the boundary is kept rather
                // than dropped: half a word missing from the drawing is worse than one
                // extra token in the average.
                if (end > start && cursor < until) kept += texts[index] to logprobs[index]
                cursor = end
            }
            if (kept.isEmpty()) return NONE

            val total = kept.sumOf { it.second.toDouble() }
            val runs = mutableListOf<ConfidenceRun>()
            for ((text, logprob) in kept) {
                val probability = exp(logprob.toDouble())
                val uncertain = probability < UNCERTAIN_BELOW
                val last = runs.lastOrNull()
                if (last != null && last.uncertain == uncertain) {
                    // Merged, and the run keeps the lowest probability in it rather than an
                    // average: the run exists to point at a hesitation, and averaging one
                    // in with its confident neighbours is how a hesitation disappears.
                    runs[runs.lastIndex] = last.copy(
                        text = last.text + text,
                        probability = minOf(last.probability, probability),
                    )
                } else {
                    runs += ConfidenceRun(text, probability, uncertain)
                }
            }
            return ReplyConfidence(
                runs = runs,
                perplexity = exp(-total / kept.size),
                tokenCount = kept.size,
            )
        }
    }
}

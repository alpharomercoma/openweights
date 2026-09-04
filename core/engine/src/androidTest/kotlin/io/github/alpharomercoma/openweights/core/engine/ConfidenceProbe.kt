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

package io.github.alpharomercoma.openweights.core.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.ReplyConfidence
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.exp

/**
 * What the model's own confidence actually looks like, and where to draw the line.
 *
 * The uncertainty view marks a token below [ReplyConfidence.UNCERTAIN_BELOW]. That constant
 * is currently a reasoned guess, and a threshold nobody has measured is a threshold that
 * either marks everything or nothing. This is the measurement that settles it, and it asks
 * three separate questions.
 *
 * **Is the number real at all.** A greedy reply should be confident almost everywhere, and
 * the probabilities should be probabilities: at most one, never negative, and the emitted
 * token should usually be the most likely one. A plumbing mistake shows up here as a
 * uniform distribution or as everything at 1.0.
 *
 * **Where the distribution sits.** Printed as a histogram, because the right threshold is
 * a property of the shape: if 98% of tokens are above 0.9, a line at 0.2 marks the genuine
 * forks, and if half the tokens are at 0.3 it marks the grammar.
 *
 * **Whether it separates knowing from inventing.** Three questions the model knows, three
 * whose answers do not exist. The interesting comparison is not the average, which the easy
 * tokens dominate, but the *lowest* token in the answer: an invented specific is a token the
 * model had to choose, and a recalled one is not. If the minimum separates them and the mean
 * does not, the screen is right to lead with the places rather than the number.
 *
 * Prints rather than asserts, except for the plumbing checks. What a good threshold is
 * belongs in `docs/research/uncertainty.md` beside the histogram it came from, not in a
 * bound here that would go stale on the next model.
 *
 * ```
 * adb push LFM2.5-1.2B-Instruct-Q4_0.gguf /data/local/tmp/openweights/model.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ConfidenceProbe {
    private lateinit var engine: InferenceEngine

    @Before
    fun setUp() {
        assumeTrue("no model at ${MODEL.path}", MODEL.isFile)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.close() }
    }

    @Test
    fun theReportedProbabilitiesAreProbabilities() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))

        val completed = ask("Name the capital of France in one word.")
        assertThat(completed.tokens).isNotEmpty()

        for (token in completed.tokens) {
            // A log probability is at most zero. Anything above it is a normalisation that
            // did not normalise, which would make every perplexity below one.
            assertThat(token.logprob).isAtMost(0f)
            assertThat(token.probability).isAtLeast(0.0)
            assertThat(token.probability).isAtMost(1.0)
        }

        // Greedy on a question with one answer: the model should be sure of nearly all of
        // it. A uniform distribution over a 150k vocabulary would put every token near
        // zero, which is what a wrong logits pointer looks like.
        val confidence = ReplyConfidence.of(
            completed.tokens.map { it.text },
            completed.tokens.map { it.logprob },
        )
        Log.i(TAG, "capital of France: ppl=${confidence.perplexity} n=${confidence.tokenCount}")
        assertThat(confidence.perplexity).isLessThan(PLAUSIBLE_CEILING)
    }

    @Test
    fun theTokensPutBackTogetherAreTheReply() = runBlocking {
        // The whole feature rests on this: a span of the answer is traced to its tokens by
        // concatenation, and nothing else checks that the pieces are complete and in order.
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))

        val completed = ask("Count from one to five in words.")

        assertThat(completed.tokens.joinToString("") { it.text })
            .isEqualTo(completed.rawReply())
    }

    @Test
    fun nothingIsMeasuredWhenNobodyAsked() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))

        val completed = ask("Say hello.", measuring = false)

        // The cost is a log-softmax over the vocabulary per token. It has to be genuinely
        // off, not merely unused.
        assertThat(completed.tokens).isEmpty()
    }

    @Test
    fun theDistributionAndWhereItSeparatesKnowingFromInventing() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))

        val histogram = IntArray(BUCKETS)
        Log.i(TAG, "question | ppl | lowest | answer")
        for ((kind, question) in ANSWERABLE.map { "known" to it } + UNKNOWABLE.map {
            "invented" to it
        }) {
            val completed = ask(question)
            val confidence = ReplyConfidence.of(
                completed.tokens.map { it.text },
                completed.tokens.map { it.logprob },
            )
            for (token in completed.tokens) {
                val bucket = (token.probability * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
                histogram[bucket] += 1
            }
            Log.i(
                TAG,
                "$kind | %.2f | %.3f | %s".format(
                    confidence.perplexity,
                    confidence.leastProbable ?: 0.0,
                    completed.content.replace('\n', ' ').take(ANSWER_CHARS),
                ),
            )
        }

        // Ten buckets of width 0.1. The threshold worth shipping is wherever the mass
        // stops, and this is the only thing that can say where that is.
        histogram.forEachIndexed { bucket, count ->
            Log.i(TAG, "p %.1f to %.1f: %d".format(bucket / 10.0, (bucket + 1) / 10.0, count))
        }
        val marked = histogram.take((ReplyConfidence.UNCERTAIN_BELOW * BUCKETS).toInt()).sum()
        Log.i(
            TAG,
            "at the shipped threshold, %d of %d tokens are marked".format(
                marked,
                histogram.sum(),
            ),
        )
    }

    private suspend fun ask(
        question: String,
        measuring: Boolean = true,
    ): GenerationEvent.Completed = engine.chat(
        messages = listOf(ChatMessage.text(ChatRole.USER, question)),
        // Greedy, so two runs of the same question agree and the confidences are about the
        // model rather than about a seed.
        params = SamplerParams(
            temperature = 0f,
            maxTokens = BUDGET,
            seed = 7,
            measuresConfidence = measuring,
        ),
    ).toList().filterIsInstance<GenerationEvent.Completed>().single()

    /** The reply as the engine produced it, thinking block included. */
    private fun GenerationEvent.Completed.rawReply(): String =
        if (reasoning.isEmpty()) content else reasoning + content

    private companion object {
        const val TAG = "OpenWeightsConfidence"
        const val CONTEXT = 4096
        const val BUDGET = 120
        const val BUCKETS = 10
        const val ANSWER_CHARS = 90

        /**
         * A perplexity a greedy reply to a settled question should stay under.
         *
         * Loose on purpose. It is a plumbing check, not a quality bar: anything under this
         * means the numbers are probabilities of a peaked distribution, and a broken
         * normalisation would be orders of magnitude above it.
         */
        const val PLAUSIBLE_CEILING = 5.0

        /** Things a 1B model has actually seen, where the tokens should be cheap. */
        val ANSWERABLE = listOf(
            "What is the capital of France? Answer in one word.",
            "Who wrote Pride and Prejudice? Answer with a name only.",
            "What is 12 times 12? Answer with digits only.",
        )

        /**
         * Things with no answer, where every specific has to be chosen.
         *
         * Named people and figures that do not exist, so the model cannot recall and must
         * invent. If confidence is worth anything, this is where it should show.
         */
        val UNKNOWABLE = listOf(
            "What is the population of Thornbury-on-Vell? Answer with a number only.",
            "In what year did Marisol Kepplewhite win her prize? Answer with a year only.",
            "How tall is the Vandersteen Tower in metres? Answer with a number only.",
        )

        /**
         * The tier fixture every engine test on this device uses, so the probe needs no
         * push of its own beyond the one already documented.
         */
        val MODEL = File("/data/local/tmp/openweights/model.gguf")

        /** Kept for reading a log line back into a probability by hand. */
        @Suppress("unused")
        fun probabilityOf(logprob: Float) = exp(logprob.toDouble())
    }
}

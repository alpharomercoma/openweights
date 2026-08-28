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

package io.github.alpharomercoma.openweights.ui.discover

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.GenerationStats
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What codex and agy asked for after the last on-device measurements: repeated, controlled
 * runs, not one sample per model.
 *
 * A single chat reply per model told us the formula's real prediction error was between 23%
 * and 130% on this device, but not whether that spread is real or measurement noise — a
 * 178-260 token sample on a phone whose thermal state and background load were never
 * controlled could plausibly explain a third of that range by itself.
 *
 * The shape (fixed token counts, discarded warmup, five measured reps) is not invented here:
 * it approximates llama.cpp's own `llama-bench` convention — pp512/tg128, 512 tokens of
 * prompt processing and 128 of generation — the shape almost every published llama.cpp
 * number on the internet already uses, and it matches what Liquid AI's own Pipette benchmark
 * (liquid.ai/blog/pipette-on-device-ai-benchmarking-by-liquid-ai) documents: fixed token
 * shapes, greedy decoding, a discarded warm-up, five measured repetitions.
 *
 * [BENCHMARK_PROMPT] itself is not invented text, on the same reasoning: it is drawn from
 * IFEval, one of the two datasets (with TinyMMLU) MLCommons' own MLPerf Mobile v6.0 — the
 * current industry benchmark for on-device Android LLM inference, released June 2026 — uses
 * to drive its GenAI tests. An earlier version of this class used a hand-written prompt
 * (first a real question, then Lorem-ipsum-style filler sized to ~512 tokens), and both were
 * a step short of this: idiosyncratic content that made the numbers hard to compare to
 * anything published, however controlled the shape was.
 *
 * What this does not replicate from Pipette: readiness gating (a thermal/load check before
 * each timed repetition, publishing only runs that pass). Nothing here checks device state
 * before a rep; the repeated-median-and-spread report is this harness's substitute.
 *
 * Run with:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=...DecodeSpeedBenchmark
 * and read the `OWBenchmark` tag in logcat.
 */
@RunWith(AndroidJUnit4::class)
class DecodeSpeedBenchmark {
    @Test
    fun measureDecodeSpeedAcrossInstalledModels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        assumeTrue("no models directory at $modelsDir", modelsDir.isDirectory)

        val models = MODEL_NAMES.mapNotNull { name ->
            File(modelsDir, name).takeIf { it.isFile }
        }
        assumeTrue("none of the expected models are downloaded", models.isNotEmpty())

        // Deliberately not the download order: the drift bookend is one specific file
        // (LFM2.5-1.2B), first and last, not "any model whose name starts with LFM2.5" —
        // an earlier version of this used startsWith("LFM2.5") for both the bookend pick
        // and the "everything else" filter, which silently dropped LFM2.5-2.6B from every
        // run entirely, since it also starts with "LFM2.5" and filterNot excluded it too.
        val driftBookend = models.first { it.name == "LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf" }
        val order = (
            listOf(driftBookend) +
                models.filterNot { it === driftBookend } +
                listOf(driftBookend)
            )

        val results = mutableListOf<String>()
        order.forEachIndexed { index, model ->
            val stats = benchmarkOneModel(model)
            val label = "${model.name}#${index + 1}"

            val decode = stats.map { it.decodeTokensPerSecond ?: 0.0 }.sorted()
            val decodeMedian = decode[decode.size / 2]
            // Prefill throughput, not just decode: a model that reads a prompt slowly is
            // felt on every turn just as much as one that writes slowly, and the two are
            // not the same number — prefill is usually compute-bound where decode is
            // usually bandwidth-bound, so a formula tuned on one says nothing about the
            // other. Recorded here because the first version of this benchmark discarded
            // it, which is a gap noted when reviewing what else the harness should cover.
            val prefill = stats.mapNotNull { it.prefillTokensPerSecond }.sorted()
            val prefillMedian = if (prefill.isNotEmpty()) prefill[prefill.size / 2] else 0.0

            val line = "OWBenchmark $label bytes=${model.length()} reps=${stats.size} " +
                "decodeTps=${decode.map { "%.2f".format(it) }} " +
                "decodeMedian=%.2f decodeMin=%.2f decodeMax=%.2f ".format(
                    decodeMedian,
                    decode.first(),
                    decode.last(),
                ) +
                "prefillTps=${prefill.map { "%.2f".format(it) }} " +
                "prefillMedian=%.2f promptTokens=${stats.first().promptTokens}".format(
                    prefillMedian,
                )
            Log.i(TAG, line)
            results += line
        }

        Log.i(TAG, "OWBenchmark DONE")
        // No assertion: this is a measurement run, not a correctness gate. See the class doc.
        assumeTrue(results.isNotEmpty())
    }

    /** One warmup rep (discarded), then [REPS] measured reps, all at a fixed token budget. */
    private suspend fun benchmarkOneModel(model: File): List<GenerationStats> {
        val engine: InferenceEngine = LlamaCppEngine()
        try {
            engine.load(model, ModelLoadParams(contextLength = CONTEXT_LENGTH))

            // Warmup: first inference after a cold load pays one-time costs (mmap page-in,
            // allocator growth) that a steady-state number should not be charged for.
            engine.resetContext()
            runOnce(engine)

            return (1..REPS).map {
                // Every rep starts from an empty cache, so every rep pays the same fixed
                // prefill and decodes the same fixed budget — a rep is never cheaper just
                // because the previous one happened to share a prefix with it.
                engine.resetContext()
                runOnce(engine)
            }
        } finally {
            engine.close()
        }
    }

    private suspend fun runOnce(engine: InferenceEngine) = engine.chat(
        messages = listOf(ChatMessage.text(ChatRole.USER, BENCHMARK_PROMPT)),
        params = SamplerParams(temperature = 0f, maxTokens = TOKEN_BUDGET, seed = 7),
    ).toList().filterIsInstance<GenerationEvent.Completed>().last().stats

    private companion object {
        const val TAG = "OWBenchmark"
        const val CONTEXT_LENGTH = 4096

        /** llama-bench's tg128: 128 generated tokens, not this class's own invention. */
        const val TOKEN_BUDGET = 128
        const val REPS = 5

        /**
         * Real prompts from IFEval (instruction_following_eval), not invented text: this is
         * one of the two datasets MLCommons' MLPerf Mobile v6.0 (June 2026) — the actual
         * industry benchmark for on-device Android LLM inference — uses to drive its GenAI
         * tests, alongside TinyMMLU. Source: github.com/google-research/google-research,
         * instruction_following_eval/data/input_data.jsonl (Apache-2.0), items keyed 1000,
         * 1001, 1005, 1069, 1122, 1148, 1213(-ish; sampled at roughly every tenth line
         * through the first 90) — quoted verbatim, not paraphrased.
         *
         * Eleven of these are read-only context, approximating pp512's ~512-token prompt
         * shape with real user-request text instead of filler. The twelfth ("short
         * proposal... at least 250 words") is what the model is asked to answer, so decode
         * is a genuine instruction-following completion rather than an arbitrary token
         * count — cut off at [TOKEN_BUDGET] like every other model here, per tg128.
         */
        val BENCHMARK_PROMPT =
            "Here is some earlier conversation, for context only — do not respond to " +
                "any of it:\n\n" +
                "1. Write a 300+ word summary of the wikipedia page " +
                "\"https://en.wikipedia.org/wiki/Raymond_III,_Count_of_Tripoli\". Do not " +
                "use any commas and highlight at least 3 sections that has titles in " +
                "markdown format, for example *highlighted section part 1*, *highlighted " +
                "section part 2*, *highlighted section part 3*.\n" +
                "2. I am planning a trip to Japan, and I would like thee to write an " +
                "itinerary for my journey in a Shakespearean style. You are not allowed " +
                "to use any commas in your response.\n" +
                "3. Write a resume for a fresh high school graduate who is seeking their " +
                "first job. Make sure to include at least 12 placeholder represented by " +
                "square brackets, such as [address], [name].\n" +
                "4. Write a long email template that invites a group of participants to " +
                "a meeting, with at least 500 words. The email must include the keywords " +
                "\"correlated\" and \"experiencing\" and should not use any commas.\n" +
                "5. make a tweet for playboy's twitter account without using capital " +
                "letters. Include at least 4 hashtags, starting with '#'\n" +
                "6. What are the advantages and disadvantages of having supernatural " +
                "powers? Make it short. Wrap the entire output in JSON format. You can " +
                "use markdown ticks such as ```.\n" +
                "7. Which one is a better brand for sneakers: Prada or Nike? Your entire " +
                "response should be in English, and in all capital letters. At the end " +
                "of your response, please explicitly add a postscript starting with " +
                "P.S. The word sneaker should appear 10 or more times in your response.\n" +
                "8. I have a dime. What can I do with this dime? Give me advice in the " +
                "style of a President of the United States and make sure it has at " +
                "least 600 words.\n" +
                "9. Compose song lyrics about a socio-economic problem. The song should " +
                "be in English and in all lowercase letters.\n" +
                "10. I'm interested in a college with open enrollment and a regional " +
                "accreditation. Which college would you recommend? Don't include the " +
                "keywords \"DuPage\" and \"Dade\" in your response. Let's make it a " +
                "constrained writing problem: be sure the letter p appears at least 15 " +
                "times in your response.\n" +
                "11. Write a casual, interesting, and weird resume for Antonia Maj who " +
                "is applying for a job at a coffee company. They have experience in " +
                "marketing, customer service, and sales. They are educated but not " +
                "majored in anything related to coffee. Make sure to include at least " +
                "two sections marking the beginning of each section with 'SECTION X'. " +
                "In your entire response make sure to use exactly two bullet points in " +
                "markdown format. Please use the following bullet point format: * Text " +
                "for bullet 1 * Text for bullet 2\n\n" +
                "Now respond to this request: Write a short proposal for a new research " +
                "project that investigates how language evolves over time. I want to " +
                "make it challenging, so: 1. Do not include any commas in your " +
                "response. 2. Do not include the letter \"c\" anywhere in your " +
                "response. 3. Your response should contain at least 250 words."
    }
}

/** Every model this device's benchmarks run against, if downloaded. Shared with ToolRefusalTest. */
internal val MODEL_NAMES = listOf(
    "LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf",
    "LFM2.5-2.6B-QAD-Q4_0.gguf",
    "Qwen3-1.7B-Q4_K_M.gguf",
    "granite-4.2-3b-Q2_K.gguf",
    "ai9stars_G9v3-3B-Q4_K_M.gguf",
    "ling-3.0-tiny-Q4_K_M.gguf",
    "Nanbeige4.2-3B-Q4_K_M.gguf",
)

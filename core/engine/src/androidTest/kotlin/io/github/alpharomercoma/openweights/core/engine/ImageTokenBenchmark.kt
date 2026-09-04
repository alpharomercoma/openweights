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
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What an image costs, and what it is worth, at each budget the slider offers.
 *
 * The question this answers is the one a user asks after waiting fifty seconds for a
 * picture to be described: where does the time go, and can it be bought back. A picture
 * does not arrive as text. The projector cuts it into patches, runs a vision transformer
 * over them, and hands the language model a block of embeddings which then has to be
 * prefilled like any other prompt and then sits in the KV cache. The number of those
 * embeddings is the one thing about that chain a user can change, and this measures both
 * halves of the trade at once: seconds on one side, whether the answer is still right on
 * the other.
 *
 * Quality is graded on reading rather than on describing, deliberately. Anything can say
 * "a report with a table in it" from a thumbnail; only a budget that survived the resize
 * can say the change on Flanges was 12.8%. The probe image carries facts at four sizes so
 * the result says *where* legibility breaks rather than only that it did.
 *
 * Not an assertion. It prints a table and the table is the output; what a good default is
 * is a judgement about seconds against correctness, and it belongs in
 * docs/research/image-tokens.md rather than in a threshold here that would go stale on the
 * next phone. Push the fixtures first:
 * ```
 * adb push LFM2.5-VL-3B-Q4_0.gguf /data/local/tmp/openweights/vl.gguf
 * adb push mmproj-LFM2.5-VL-3B-Q8_0.gguf /data/local/tmp/openweights/mmproj.gguf
 * python3 tools/vision-probe-image.py /tmp/vl-probe.png
 * adb push /tmp/vl-probe.png /data/local/tmp/openweights/vl-probe.png
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ImageTokenBenchmark {
    private var engine: InferenceEngine? = null

    @After
    fun tearDown() {
        engine?.let { runBlocking { it.close() } }
    }

    /**
     * The sweep at the size the app actually sends, which is the one that decides the
     * default.
     *
     * `AttachmentStore.MAX_IMAGE_EDGE` shrinks every attachment to a longest edge of 1024
     * before it is stored, so this is the picture a real turn hands the projector. It is
     * also the sweep in which the budget does anything at all: see the other test.
     */
    @Test
    fun atTheSizeTheAppSendsEveryBudgetIsMeasured() = runBlocking {
        sweep(APP_SIZED_IMAGE)
    }

    /**
     * The same sweep on the picture as it came off the camera, which is the control.
     *
     * Kept because it is the measurement that explains the first one. LFM2's preprocessor
     * cuts an image into 512-pixel tiles when the original is more than twice its pixel
     * budget, and every tile is a fixed 256 tokens whatever the budget says, so on a
     * full-resolution photograph the slider moves the thumbnail and nothing else.
     */
    @Test
    fun atFullResolutionTheBudgetIsMeasuredToDoAlmostNothing() = runBlocking {
        sweep(FULL_SIZE_IMAGE)
    }

    /**
     * The other lever, and the one that turns out to matter: how large a picture is sent.
     *
     * `AttachmentStore` already shrinks every attachment to a longest edge of 1024, and
     * that number was chosen by argument rather than by measurement. This is the
     * measurement. Every arm uses the projector's own token limits, so the only thing
     * moving is the picture.
     */
    @Test
    fun everyResolutionIsMeasuredForSpeedAndForWhatItCanStillRead() = runBlocking {
        assumeTrue("no vision model at ${MODEL.path}", MODEL.isFile)
        assumeTrue("no projector at ${PROJECTOR.path}", PROJECTOR.isFile)

        Log.i(TAG, "longest edge sweep, projector limits left alone")
        Log.i(TAG, "edge | promptTok | encode+prefill | decode | total | answers")
        for (edge in EDGES) {
            val image = File("/data/local/tmp/openweights/vl-probe-$edge.jpg")
            if (!image.isFile) {
                Log.i(TAG, "$edge | missing")
                continue
            }
            Log.i(TAG, "$edge px: " + measure(AUTOMATIC, image))
        }
    }

    private suspend fun sweep(image: File) {
        assumeTrue("no vision model at ${MODEL.path}", MODEL.isFile)
        assumeTrue("no projector at ${PROJECTOR.path}", PROJECTOR.isFile)
        assumeTrue("no probe image at ${image.path}", image.isFile)

        Log.i(TAG, "image ${image.name}")
        Log.i(TAG, "budget | promptTok | encode+prefill | decode | total | answers")
        for (budget in BUDGETS) {
            Log.i(TAG, measure(budget, image))
        }
    }

    /**
     * One budget, loaded from scratch.
     *
     * A fresh load per arm rather than a reload of the context: the projector reads its
     * token limits once, when it is opened, so an arm that reused an open one would be
     * measuring the previous arm's setting. It costs a few seconds each and buys a result
     * that means what it says.
     */
    private suspend fun measure(budget: Int, image: File): String {
        engine?.close()
        val fresh = LlamaCppEngine()
        engine = fresh
        fresh.load(
            MODEL,
            ModelLoadParams(contextLength = CONTEXT, imageTokens = budget),
            PROJECTOR,
        )

        val startedAt = System.nanoTime()
        val completed = fresh.chat(
            messages = listOf(
                ChatMessage(
                    role = ChatRole.USER,
                    parts = listOf(
                        MessagePart.File(image.absolutePath, image.mediaType()),
                        MessagePart.Text(QUESTION),
                    ),
                ),
            ),
            // Greedy, so two arms differ because the picture differed and not because the
            // sampler did.
            params = SamplerParams(temperature = 0f, maxTokens = BUDGET_TOKENS, seed = 7),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()
        val wallMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

        val stats = completed.stats
        val answer = completed.content
        // Which of the four facts survived, largest print first. The prompt names the
        // question each one answers, so a miss is the model failing to read rather than
        // failing to mention.
        val read = FACTS.filter { (_, needle) -> answer.contains(needle, ignoreCase = true) }
            .joinToString("+") { (name, _) -> name }
            .ifEmpty { "none" }

        Log.i(TAG, "answer at $budget: ${answer.replace('\n', ' ')}")
        return "$budget | ${stats.promptTokens} | ${stats.prefillMs} ms | " +
            "${stats.decodeMs} ms | $wallMillis ms | $read"
    }

    private companion object {
        const val TAG = "OpenWeightsImageTokens"
        const val CONTEXT = 8192
        const val BUDGET_TOKENS = 400
        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Automatic first, then the range the slider offers, in the order they are read.
         *
         * Zero is [ModelLoadParams.AUTOMATIC_IMAGE_TOKENS] and is the control: it is what
         * the app does today, so every other row is a difference from it rather than from
         * an abstraction.
         */
        val BUDGETS = listOf(0, 16, 32, 64, 128, 256, 512, 1024)

        /** [ModelLoadParams.AUTOMATIC_IMAGE_TOKENS], named where it is used as one. */
        const val AUTOMATIC = 0

        /**
         * Longest edges to send the same picture at, in pixels.
         *
         * 1024 is what the app does today. The two above it are there to show what is being
         * given up, and the two below to show where reading fails.
         */
        val EDGES = listOf(384, 512, 768, 1024, 1536, 2048)

        /**
         * One question that needs all four sizes of text answered at once.
         *
         * Asked as one turn rather than four so every arm reads the same picture the same
         * number of times: four turns would let a later one answer from the first one's
         * reply rather than from the image.
         */
        const val QUESTION =
            "Read this screenshot and answer with four short lines: the heading, " +
                "the number in the green circle, the percentage change on the Flanges " +
                "row, and the verification code at the bottom."

        /**
         * What is actually in the picture, largest print first.
         *
         * The heading is 72pt and survives anything; the code is 24pt and is the first
         * thing a small budget loses. Between them the trade is visible rather than
         * binary.
         */
        val FACTS = listOf(
            "heading" to "Quarterly Report",
            "circle" to "42",
            "table" to "12.8",
            "smallprint" to "TANGERINE",
        )

        val MODEL = File("/data/local/tmp/openweights/vl.gguf")
        val PROJECTOR = File("/data/local/tmp/openweights/mmproj.gguf")

        /** The picture as a phone takes it, 1080 by 2400. */
        val FULL_SIZE_IMAGE = File("/data/local/tmp/openweights/vl-probe.png")

        /** The same picture after `AttachmentStore`, longest edge 1024. */
        val APP_SIZED_IMAGE = File("/data/local/tmp/openweights/vl-probe-1024.jpg")

        fun File.mediaType(): String =
            if (extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
    }
}

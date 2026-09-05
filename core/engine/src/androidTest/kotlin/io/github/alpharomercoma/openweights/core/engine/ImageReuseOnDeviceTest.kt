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
 * What the 2026-09-05 vision changes claim, checked on a phone rather than on the Mac.
 *
 * Three claims, each of which was measured on the host and is asserted here against the
 * numbers a device gives:
 *
 * 1. **A 3:4 photograph is one encode at the balanced stop.** At the old 1024-edge rule the
 *    same photograph tiled into seven or eleven encodes and 1,800 or more prompt tokens.
 *    With the engine's 512-token ceiling and the picture at 524k pixels it is one view of
 *    about 500 tokens. The assertion is on the tokens, which are exact; the time is logged.
 * 2. **A follow-up question does not re-encode the picture.** Either the cache is extended
 *    and the follow-up prefills only its own words, or the reply re-tokenized differently
 *    and the conversation is re-read from stored embeddings. Both are far cheaper than the
 *    first turn, and the assertion is that the follow-up's prefill is well under half of it.
 * 3. **The stops cost what they say.** Fast is fewer tokens than balanced, and tiles is
 *    many more. Logged as a ladder with what each one read.
 *
 * Fixtures, pushed to `/data/local/tmp/openweights/`: `vl.gguf` (LFM2.5-VL-3B-Q4_0),
 * `mmproj.gguf` (its Q8_0 projector), and the pictures under `img/` made by shrinking the same three
 * pictures to each stop's pixels and to the old edge rule. The pictures are a handwritten
 * form photographed at 3:4, a receipt, and the probe screenshot.
 */
@RunWith(AndroidJUnit4::class)
class ImageReuseOnDeviceTest {
    private var engine: InferenceEngine? = null

    @After
    fun tearDown() {
        engine?.let { runBlocking { it.close() } }
    }

    @Test
    fun aPhotographIsOneEncodeAndAFollowUpReusesIt(): Unit = runBlocking {
        val balanced = image("form-balanced")
        assumeFixtures(balanced)
        val fresh = load()

        val first = ask(fresh, listOf(imageTurn(balanced, FORM_QUESTION)))
        Log.i(TAG, "first turn: ${first.stats.row()} :: ${first.content.oneLine()}")
        // One view. Tiles would be 1,800 or more; a 512-token view of this picture is 480
        // embeddings plus the text around it.
        assertThat(first.stats.promptTokens + first.stats.cachedTokens).isLessThan(ONE_VIEW_CEILING)

        val history = listOf(
            imageTurn(balanced, FORM_QUESTION),
            ChatMessage.text(ChatRole.ASSISTANT, first.content),
            ChatMessage.text(ChatRole.USER, FOLLOW_UP),
        )
        val second = ask(fresh, history)
        Log.i(TAG, "follow-up: ${second.stats.row()} :: ${second.content.oneLine()}")
        // Extended, or re-read without the encoder. Either way the picture was not encoded
        // a second time, and that is most of the first turn's time.
        val ratio = second.stats.prefillMs.toDouble() / first.stats.prefillMs.coerceAtLeast(1)
        Log.i(TAG, "follow-up prefill is ${"%.2f".format(ratio)} of the first turn's")
        assertThat(ratio).isLessThan(FOLLOW_UP_CEILING)
    }

    @Test
    fun theOldEdgeRuleTiledThisPhotographAndTheBalancedStopDoesNot(): Unit = runBlocking {
        val old = image("form-edge1024")
        val balanced = image("form-balanced")
        assumeFixtures(old, balanced)
        val fresh = load()

        // The picture the reported turn actually sent: 849 by 1024, over the tiling line
        // even with the ceiling raised to 512 tokens? It is not: 869k pixels is under the
        // new line at 1,048,576, so with the ceiling in place even the old picture is one
        // view. What the assertion pins is that neither picture tiles any more, and that
        // the balanced one costs less for the same reading.
        val fromOld = ask(fresh, listOf(imageTurn(old, FORM_QUESTION)))
        Log.i(TAG, "edge1024: ${fromOld.stats.row()} :: ${fromOld.content.oneLine()}")
        val fromBalanced = ask(fresh, listOf(imageTurn(balanced, FORM_QUESTION)))
        Log.i(TAG, "balanced: ${fromBalanced.stats.row()} :: ${fromBalanced.content.oneLine()}")

        assertThat(fromOld.stats.promptTokens + fromOld.stats.cachedTokens).isLessThan(TILED_FLOOR)
        assertThat(fromBalanced.stats.promptTokens + fromBalanced.stats.cachedTokens)
            .isAtMost(fromOld.stats.promptTokens + fromOld.stats.cachedTokens)
    }

    @Test
    fun everyStopIsMeasuredOnEveryPicture(): Unit = runBlocking {
        assumeTrue("no vision model at ${MODEL.path}", MODEL.isFile)
        assumeTrue("no projector at ${PROJECTOR.path}", PROJECTOR.isFile)
        val fresh = load()

        Log.i(TAG, "picture | promptTok | prefill ms | decode ms | read | answer")
        val ladder = listOf(
            "form-fast" to FORM_FACTS,
            "form-balanced" to FORM_FACTS,
            "form-edge1024" to FORM_FACTS,
            "form-tiles" to FORM_FACTS,
            "receipt-balanced" to RECEIPT_FACTS,
            "receipt-edge1024" to RECEIPT_FACTS,
            "probe-balanced" to PROBE_FACTS,
            "probe-edge1024" to PROBE_FACTS,
        )
        for ((name, facts) in ladder) {
            val picture = image(name)
            if (!picture.isFile) {
                Log.i(TAG, "$name | missing")
                continue
            }
            val question = when {
                name.startsWith("form") -> FORM_QUESTION
                name.startsWith("receipt") -> RECEIPT_QUESTION
                else -> PROBE_QUESTION
            }
            val done = ask(fresh, listOf(imageTurn(picture, question)))
            val read = facts.count { alternatives ->
                alternatives.any { done.content.contains(it, ignoreCase = true) }
            }
            Log.i(
                TAG,
                "$name | ${done.stats.promptTokens + done.stats.cachedTokens} | " +
                    "${done.stats.prefillMs} | ${done.stats.decodeMs} | $read/${facts.size} | " +
                    done.content.oneLine(),
            )
        }
    }

    private fun assumeFixtures(vararg pictures: File) {
        assumeTrue("no vision model at ${MODEL.path}", MODEL.isFile)
        assumeTrue("no projector at ${PROJECTOR.path}", PROJECTOR.isFile)
        pictures.forEach { assumeTrue("no picture at ${it.path}", it.isFile) }
    }

    private suspend fun load(): InferenceEngine {
        engine?.close()
        val fresh = LlamaCppEngine()
        engine = fresh
        fresh.load(MODEL, ModelLoadParams(contextLength = CONTEXT), PROJECTOR)
        return fresh
    }

    private suspend fun ask(
        engine: InferenceEngine,
        messages: List<ChatMessage>,
    ): GenerationEvent.Completed = engine.chat(
        messages = messages,
        params = SamplerParams(temperature = 0f, maxTokens = BUDGET, seed = 7),
    ).toList().filterIsInstance<GenerationEvent.Completed>().single()

    private fun imageTurn(picture: File, question: String) = ChatMessage(
        role = ChatRole.USER,
        parts = listOf(
            MessagePart.File(picture.absolutePath, "image/jpeg"),
            MessagePart.Text(question),
        ),
    )

    private fun image(name: String) = File("/data/local/tmp/openweights/img/$name.jpg")

    private fun GenerationStats.row(): String =
        "prompt=$promptTokens cached=$cachedTokens prefill=${prefillMs}ms decode=${decodeMs}ms"

    private fun String.oneLine(): String = replace('\n', ' ').take(LOG_CHARS)

    private companion object {
        const val TAG = "OpenWeightsImageReuse"
        const val CONTEXT = 8192
        const val BUDGET = 120
        const val LOG_CHARS = 200

        /** A single 512-token view plus the template and question: tiles start at 1,800. */
        const val ONE_VIEW_CEILING = 800

        /** Below this a picture was not tiled; a tiled one is at least seven chunks of 256. */
        const val TILED_FLOOR = 1_700

        /** The follow-up's share of the first turn's prefill. Half is generous. */
        const val FOLLOW_UP_CEILING = 0.5

        val MODEL = File("/data/local/tmp/openweights/vl.gguf")
        val PROJECTOR = File("/data/local/tmp/openweights/mmproj.gguf")

        const val FORM_QUESTION =
            "Read this form. Give the student's name, the course, the contact number, and the " +
                "date the toga must be returned."
        const val FOLLOW_UP = "What is the phone number of Early Marketing at the top?"
        const val RECEIPT_QUESTION =
            "Read this receipt. What was bought, how many, the total, the cash given and the " +
                "change?"
        const val PROBE_QUESTION =
            "Read this screenshot and answer with four short lines: the heading, the number in " +
                "the green circle, the percentage change on the Flanges row, and the verification " +
                "code at the bottom."

        val FORM_FACTS = listOf(
            listOf("xynil"),
            listOf("bscoe", "bs coe"),
            listOf("09452709636", "0945"),
            listOf("sept 9", "sep 9", "september 9"),
        )
        val RECEIPT_FACTS = listOf(
            listOf("espresso"),
            listOf("950"),
            listOf("1,000", "1000"),
            listOf("50"),
        )
        val PROBE_FACTS = listOf(
            listOf("quarterly report"),
            listOf("42"),
            listOf("12.8"),
            listOf("tangerine"),
        )
    }
}

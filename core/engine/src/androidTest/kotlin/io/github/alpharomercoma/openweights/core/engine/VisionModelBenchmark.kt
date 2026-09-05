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
import androidx.test.platform.app.InstrumentationRegistry
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
 * One vision model, the same four pictures, whatever the caller wants to vary.
 *
 * Not a test of the app but an instrument for the question the 2026-09-05 phone run left
 * open: the vision tower took 35 to 43 s of every first turn on the MediaTek phone, against
 * 12.6 s on the Snapdragon. Which model, and which knobs, change that is what this measures,
 * and the answer is a table in logcat under [TAG], one row per picture, with what the model
 * read of the planted facts so that speed is never reported without the cost in reading.
 *
 * Everything is an instrumentation argument, so one APK serves every run:
 *
 * ```
 * am instrument -w -r -e class ...VisionModelBenchmark \
 *   -e model /data/local/tmp/openweights/vlm/qwen3vl.gguf \
 *   -e mmproj /data/local/tmp/openweights/vlm/qwen3vl-mmproj.gguf \
 *   -e label qwen3vl-2b [-e threads 6] [-e batchThreads 8] [-e gpuLayers 99] \
 *   [-e pictures form-balanced,receipt-balanced] [-e imageTokens 0]
 *   io.github.alpharomercoma.openweights.core.engine.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The pictures are the ones `ImageReuseOnDeviceTest` measures, already sized for the
 * balanced stop, so a model that sizes pictures its own way (Qwen3-VL's dynamic grid,
 * Gemma 3's fixed square, SmolVLM's 512-pixel tiles) is handed the same file and the
 * tokens it makes of it are part of the result. Encode time is in the engine's own
 * `media: encoded N tokens in M ms` line, next to these rows in logcat.
 */
@RunWith(AndroidJUnit4::class)
class VisionModelBenchmark {
    private var engine: InferenceEngine? = null

    @After
    fun tearDown() {
        runBlocking { engine?.close() }
    }

    @Test
    fun everyPictureThroughThisModel(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val model = File(args.getString("model") ?: DEFAULT_MODEL)
        val projector = File(args.getString("mmproj") ?: DEFAULT_PROJECTOR)
        val label = args.getString("label") ?: model.nameWithoutExtension
        val threads = args.getString("threads")?.toIntOrNull()
        val batchThreads = args.getString("batchThreads")?.toIntOrNull()
        val gpuLayers = args.getString("gpuLayers")?.toIntOrNull() ?: 0
        val imageTokens = args.getString("imageTokens")?.toIntOrNull() ?: 0
        val pictures = (args.getString("pictures") ?: DEFAULT_PICTURES).split(',')
        assumeTrue("no model at ${model.path}", model.isFile)
        assumeTrue("no projector at ${projector.path}", projector.isFile)

        val fresh = LlamaCppEngine()
        engine = fresh
        val loadStart = System.currentTimeMillis()
        fresh.load(
            model,
            ModelLoadParams(
                contextLength = CONTEXT,
                threadCount = threads,
                batchThreadCount = batchThreads,
                gpuLayers = gpuLayers,
                imageTokens = imageTokens,
            ),
            projector,
        )
        Log.i(
            TAG,
            "$label: loaded in ${System.currentTimeMillis() - loadStart} ms " +
                "(threads=$threads batchThreads=$batchThreads gpuLayers=$gpuLayers " +
                "imageTokens=$imageTokens)",
        )
        Log.i(TAG, "$label | picture | promptTok | prefill ms | decode ms | read | answer")
        for (name in pictures) {
            val picture = File("/data/local/tmp/openweights/img/$name.jpg")
            if (!picture.isFile) {
                Log.i(TAG, "$label | $name | missing")
                continue
            }
            val (question, facts) = questionFor(name)
            val done = fresh.chat(
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.USER,
                        parts = listOf(
                            MessagePart.File(picture.absolutePath, "image/jpeg"),
                            MessagePart.Text(question),
                        ),
                    ),
                ),
                params = SamplerParams(
                    temperature = 0f,
                    maxTokens = BUDGET,
                    seed = 7,
                    thinking = false,
                ),
            ).toList().filterIsInstance<GenerationEvent.Completed>().single()
            val read = facts.count { alternatives ->
                alternatives.any { done.content.contains(it, ignoreCase = true) }
            }
            Log.i(
                TAG,
                "$label | $name | ${done.stats.promptTokens + done.stats.cachedTokens} | " +
                    "${done.stats.prefillMs} | ${done.stats.decodeMs} | $read/${facts.size} | " +
                    done.content.replace('\n', ' ').take(LOG_CHARS),
            )
        }
    }

    private fun questionFor(name: String): Pair<String, List<List<String>>> = when {
        name.startsWith("form") ->
            ImageReuseOnDeviceTest.FORM_QUESTION to ImageReuseOnDeviceTest.FORM_FACTS
        name.startsWith("receipt") ->
            ImageReuseOnDeviceTest.RECEIPT_QUESTION to ImageReuseOnDeviceTest.RECEIPT_FACTS
        name.startsWith("page") ->
            ImageReuseOnDeviceTest.PAGE_QUESTION to ImageReuseOnDeviceTest.PAGE_FACTS
        else -> ImageReuseOnDeviceTest.PROBE_QUESTION to ImageReuseOnDeviceTest.PROBE_FACTS
    }

    private companion object {
        const val TAG = "OpenWeightsVisionBench"
        const val CONTEXT = 8192
        const val BUDGET = 120
        const val LOG_CHARS = 160
        const val DEFAULT_MODEL = "/data/local/tmp/openweights/vl.gguf"
        const val DEFAULT_PROJECTOR = "/data/local/tmp/openweights/mmproj.gguf"
        const val DEFAULT_PICTURES = "form-balanced,receipt-balanced,probe-balanced,page-balanced"
    }
}

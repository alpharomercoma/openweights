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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A compiled vision model reads a picture through the engine, on the phone.
 *
 * The runtime this app ships was proven to drive Software Mansion's LFM2.5-VL export
 * before any of this was written: the stock multimodal runner ran the encoder in 1.5 s
 * and the decoder took its output, answering "The square is red and the background is
 * blue." That probe is now this test, through the engine rather than the raw API, with
 * the picture written to disk the way an attachment is.
 *
 * ```
 * adb push lfm2_5_vl_450m_8da4w_xnnpack.pte /data/local/tmp/openweights/
 * adb push tokenizer.json /data/local/tmp/openweights/lfm2_5_vl_450m_8da4w_xnnpack.tokenizer.json
 * ```
 * Skips rather than fails without them.
 */
@RunWith(AndroidJUnit4::class)
class ExecuTorchVisionOnDeviceTest {

    private lateinit var engine: ExecuTorchEngine

    @Before
    fun setUp() {
        assumeTrue("no vision .pte at ${MODEL.path}", MODEL.isFile)
        assumeTrue("no tokenizer at ${TOKENIZER.path}", TOKENIZER.isFile)
        engine = ExecuTorchEngine(NativeExecuTorchBridge())
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.unload() }
    }

    @Test
    fun opensAsAModelThatReadsPictures(): Unit = runBlocking {
        engine.load(MODEL, PARAMS)

        val loaded = engine.loadedModel
        assertThat(loaded?.mediaSupport?.vision).isTrue()
        Log.i(TAG, "window ${loaded?.contextSize}")
    }

    @Test
    fun describesAPictureFromDisk(): Unit = runBlocking {
        engine.load(MODEL, PARAMS)
        val picture = squareOnBlue()

        val events = engine.chat(
            listOf(
                ChatMessage(
                    ChatRole.USER,
                    listOf(
                        MessagePart.File(picture.absolutePath, "image/png"),
                        MessagePart.Text(
                            "What colour is the square, and what colour is the background? " +
                                "Answer in one sentence.",
                        ),
                    ),
                ),
            ),
            SamplerParams(maxTokens = 64, thinking = false),
        ).toList()

        val done = events.filterIsInstance<GenerationEvent.Completed>().single()
        Log.i(TAG, "reply: ${done.content}")
        Log.i(TAG, "stats: ${done.stats}")
        assertThat(done.content.lowercase()).contains("red")
        assertThat(done.content.lowercase()).contains("blue")
        // The runtime counts the picture's positions: 256 visual tokens plus the text.
        assertThat(done.stats.contextUsed).isGreaterThan(256)
    }

    /** A red square on a blue field, 640 x 480 so the letterbox has work to do. */
    private fun squareOnBlue(): File {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(20, 40, 200))
            drawRect(160f, 120f, 480f, 360f, Paint().apply { color = Color.rgb(220, 30, 30) })
        }
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(dir, "square-on-blue.png").apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private companion object {
        const val TAG = "OpenWeightsVision"

        /**
         * Another export can be pointed at without a rebuild:
         * `am instrument ... -e pte <path>.pte -e tokenizer <path>.json`. The file name still has
         * to say its family, since that is how the template is chosen.
         */
        private val arguments = InstrumentationRegistry.getArguments()
        val MODEL = File(
            arguments.getString("pte")
                ?: "/data/local/tmp/openweights/lfm2_5_vl_450m_8da4w_xnnpack.pte",
        )
        val TOKENIZER = File(
            arguments.getString("tokenizer")
                ?: "/data/local/tmp/openweights/lfm2_5_vl_450m_8da4w_xnnpack.tokenizer.json",
        )
        val PARAMS = ModelLoadParams(contextLength = 4096)
    }
}

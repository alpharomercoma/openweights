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
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether this app can actually see a picture, asked of the engine rather than of the docs.
 *
 * Three separate claims sit behind "the model is multimodal" and only the last one matters:
 * that the file says vision, that the projector loads, and that a picture put in front of it
 * changes the answer. A model loaded without its projector reports no vision and refuses
 * every attachment, and a model loaded with the wrong one loads without complaint and
 * answers about nothing.
 *
 * So this draws a picture whose content it knows, sends it, and reads the reply. The picture
 * is a solid red rectangle with a black circle on it, because "what colour is this" has one
 * right answer and no amount of plausible waffle hits it by accident.
 *
 * ```
 * adb push LFM2.5-VL-3B-Q4_K_M.gguf   /data/local/tmp/openweights/vision.gguf
 * adb push mmproj-LFM2.5-VL-3B-Q8_0.gguf /data/local/tmp/openweights/vision-mmproj.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class VisionOnDeviceTest {
    private lateinit var engine: InferenceEngine

    @Before
    fun setUp() {
        Fixtures.require("no vision model at ${MODEL.path}", MODEL.isFile)
        Fixtures.require("no projector at ${PROJECTOR.path}", PROJECTOR.isFile)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.close() }
    }

    @Test
    fun aPictureReachesTheModelAndChangesTheAnswer() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = 4096), PROJECTOR)

        val support = engine.loadedModel?.mediaSupport
        Log.i(TAG, "vision=${support?.vision} audio=${support?.audio}")
        assertThat(support?.vision).isTrue()

        val picture = redSquare()
        val reply = ask(
            ChatMessage(
                role = ChatRole.USER,
                parts = listOf(
                    MessagePart.File(picture.path, "image/png", "square.png"),
                    MessagePart.Text("What colour is this image? Answer with one word."),
                ),
            ),
        )
        Log.i(TAG, "with the picture: $reply")

        // The control. The same question with no picture cannot be right for any reason
        // other than luck, and if it is right anyway then the test proves nothing.
        val blind = ask(
            ChatMessage.text(
                ChatRole.USER,
                "What colour is this image? Answer with one word.",
            ),
        )
        Log.i(TAG, "without it: $blind")

        assertThat(reply.lowercase()).contains("red")
    }

    private suspend fun ask(message: ChatMessage): String {
        val events = engine.chat(
            messages = listOf(message),
            params = SamplerParams(temperature = 0f, maxTokens = 60, seed = 1),
        ).toList()
        return events.filterIsInstance<GenerationEvent.Completed>().single().content.trim()
    }

    /** A picture with exactly one honest answer. */
    private fun redSquare(): File {
        val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(220, 30, 30))
            drawCircle(SIDE / 2f, SIDE / 2f, SIDE / 5f, Paint().apply { color = Color.BLACK })
        }
        val file = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "vision-${System.nanoTime()}.png",
        )
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private companion object {
        const val TAG = "OpenWeights"
        const val SIDE = 256
        val MODEL = File("/data/local/tmp/openweights/vision.gguf")
        val PROJECTOR = File("/data/local/tmp/openweights/vision-mmproj.gguf")
    }
}

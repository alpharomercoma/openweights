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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the projector path actually reads the picture.
 *
 * The failure this guards against is subtle: an image that never reaches the model still
 * produces a fluent, confident answer, because the language model happily describes an
 * imaginary picture. So the assertion is on a detail only visible in the file — the colour
 * and shape drawn into it — rather than on the reply merely existing.
 */
@RunWith(AndroidJUnit4::class)
class MultimodalTest {
    private lateinit var engine: InferenceEngine

    @Before
    fun setUp() {
        assumeTrue("no vision model at ${MODEL.path}", MODEL.isFile)
        assumeTrue("no projector at ${PROJECTOR.path}", PROJECTOR.isFile)
        assumeTrue("no test image at ${IMAGE.path}", IMAGE.isFile)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.close() }
    }

    @Test
    fun loadingAProjectorReportsWhatTheModelCanRead() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT), PROJECTOR)

        val support = engine.loadedModel?.mediaSupport
        Log.i(TAG, "media support: $support")

        assertThat(support?.vision).isTrue()
    }

    @Test
    fun loadingWithoutAProjectorReadsNothing() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))

        assertThat(engine.loadedModel?.mediaSupport?.any).isFalse()
    }

    @Test
    fun theModelDescribesTheImageItWasGiven() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT), PROJECTOR)

        val completed = engine.chat(
            messages = listOf(
                ChatMessage(
                    role = ChatRole.USER,
                    parts = listOf(
                        MessagePart.File(IMAGE.absolutePath, "image/png"),
                        MessagePart.Text("What shape and colour is in this image?"),
                    ),
                ),
            ),
            params = SamplerParams(temperature = 0.1f, maxTokens = BUDGET, seed = 7),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()

        Log.i(TAG, "reply=${completed.content}")
        Log.i(TAG, "stats=${completed.stats}")

        val answer = completed.content.lowercase()
        assertThat(answer).contains("red")
        assertThat(answer).contains("octagon")
    }

    @Test
    fun aTextTurnAfterAnImageTurnStillWorks() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT), PROJECTOR)

        val withImage = ChatMessage(
            role = ChatRole.USER,
            parts = listOf(
                MessagePart.File(IMAGE.absolutePath, "image/png"),
                MessagePart.Text("Name the colour in this image in one word."),
            ),
        )
        val first = engine.chat(
            messages = listOf(withImage),
            params = SamplerParams(temperature = 0.1f, maxTokens = BUDGET, seed = 7),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()

        // The image turn leaves embeddings in the cache that no token describes. If the
        // prefix-reuse path did not notice, this second turn would decode at the wrong
        // positions and answer with fluent nonsense.
        val second = engine.chat(
            messages = listOf(
                withImage,
                ChatMessage.text(ChatRole.ASSISTANT, first.content),
                ChatMessage.text(ChatRole.USER, "What is 2 + 2? Answer with digits only."),
            ),
            params = SamplerParams(temperature = 0.1f, maxTokens = BUDGET, seed = 7),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()

        Log.i(TAG, "first=${first.content} | second=${second.content}")

        assertThat(second.content).contains("4")
    }

    @Test
    fun anAudioModelHearsWhatWasSaid() = runBlocking {
        assumeTrue("no audio model at ${AUDIO_MODEL.path}", AUDIO_MODEL.isFile)
        assumeTrue("no audio projector at ${AUDIO_PROJECTOR.path}", AUDIO_PROJECTOR.isFile)
        assumeTrue("no test recording at ${RECORDING.path}", RECORDING.isFile)

        engine.load(AUDIO_MODEL, ModelLoadParams(contextLength = CONTEXT), AUDIO_PROJECTOR)
        assertThat(engine.loadedModel?.mediaSupport?.audio).isTrue()

        val completed = engine.chat(
            messages = listOf(
                ChatMessage(
                    role = ChatRole.USER,
                    parts = listOf(
                        MessagePart.File(RECORDING.absolutePath, "audio/wav"),
                        MessagePart.Text("Transcribe what you just heard, word for word."),
                    ),
                ),
            ),
            params = SamplerParams(temperature = 0.1f, maxTokens = BUDGET, seed = 7),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()

        Log.i(TAG, "heard=${completed.content}")

        // The recording says "The capital of Portugal is Lisbon, and the year was 1984."
        // Asserting on facts only present in the audio: a model that never heard it would
        // still answer fluently, just about nothing.
        val heard = completed.content.lowercase()
        assertThat(heard).contains("lisbon")
        assertThat(heard).contains("1984")
    }

    private companion object {
        const val TAG = "OpenWeightsMultimodal"
        const val CONTEXT = 4096

        /** Vision models narrate before answering; a short budget truncates mid-sentence. */
        const val BUDGET = 600

        val MODEL = File("/data/local/tmp/openweights/vl.gguf")
        val PROJECTOR = File("/data/local/tmp/openweights/mmproj.gguf")
        val IMAGE = File("/data/local/tmp/openweights/test-image.png")

        val AUDIO_MODEL = File("/data/local/tmp/openweights/audio.gguf")
        val AUDIO_PROJECTOR = File("/data/local/tmp/openweights/audio-mmproj.gguf")
        val RECORDING = File("/data/local/tmp/openweights/test-audio.wav")
    }
}

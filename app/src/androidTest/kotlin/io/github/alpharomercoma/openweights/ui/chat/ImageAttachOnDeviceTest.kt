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

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.assistantHistoryText
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.data.ModelPreferencesRepository
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import io.github.alpharomercoma.openweights.core.engine.StopReason
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.ToolNotes
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import io.github.alpharomercoma.openweights.model.AttachmentResult
import io.github.alpharomercoma.openweights.model.AttachmentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A picture through the app's own door: stored by [AttachmentStore], sent through
 * [TurnRunner] as the transcript would send it, and asked about again.
 *
 * `ImageReuseOnDeviceTest` in the engine module hands the engine a picture already sized for
 * each stop and checks the engine's side of the 2026-09-05 changes. This is the app's side:
 * that the store really shrinks a photograph by area to the stop the setting names, with a
 * real decoder rather than Robolectric's stub, and that the file it writes is then one
 * encode of about 500 tokens whose follow-up extends the cache instead of re-reading it.
 *
 * Fixtures, in `/data/local/tmp/openweights/`: `vl.gguf`, `mmproj.gguf`, and
 * `img/form-tiles.jpg`, a 2.6-megapixel photograph of a handwritten form. That one is the
 * source on purpose: it is over every stop's budget, so the store has to shrink it for all
 * three, and the tiles stop has to leave it large enough to tile.
 */
@RunWith(AndroidJUnit4::class)
class ImageAttachOnDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val preferences = ModelPreferencesRepository(context)
    private val store = AttachmentStore(context, preferences)
    private lateinit var engine: InferenceEngine

    @Before
    fun setUp() {
        Fixtures.require("no vision model at ${MODEL.path}", MODEL.isFile)
        Fixtures.require("no projector at ${PROJECTOR.path}", PROJECTOR.isFile)
        Fixtures.require("no photograph at ${SOURCE.path}", SOURCE.isFile)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.close() }
    }

    @Test
    fun theStoreShrinksAPhotographToTheStopsArea() = runBlocking {
        // Every stop, against a real decoder. The fast and balanced budgets are the pixels
        // their tokens buy; the tiles stop has to land past the tiling line for this 3:4
        // shape, which the 512-token ceiling puts at 1,048,576 rounded pixels.
        val areas = ModelPreferences.IMAGE_TOKEN_STEPS.associateWith { tokens ->
            preferences.save("", ModelPreferences(imageTokens = tokens))
            val stored = storeSource()
            val area = stored.decodedArea()
            Log.i(TAG, "stop $tokens: ${stored.path} decodes to $area pixels")
            area
        }
        assertThat(areas.getValue(ModelPreferences.IMAGE_TOKENS_FAST)).isAtMost(256L * 1024)
        assertThat(areas.getValue(ModelPreferences.IMAGE_TOKENS_FAST)).isGreaterThan(200L * 1024)
        assertThat(areas.getValue(ModelPreferences.IMAGE_TOKENS_BALANCED)).isAtMost(512L * 1024)
        assertThat(
            areas.getValue(ModelPreferences.IMAGE_TOKENS_BALANCED),
        ).isGreaterThan(450L * 1024)
        assertThat(areas.getValue(ModelPreferences.IMAGE_TOKENS_TILES)).isGreaterThan(
            2L * 512 * 1024,
        )
    }

    @Test
    fun aStoredPhotographIsOneEncodeAndItsFollowUpExtendsTheCache() = runBlocking {
        val balanced = ModelPreferences.IMAGE_TOKENS_BALANCED
        preferences.save("", ModelPreferences(imageTokens = balanced))
        val stored = storeSource()
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT), PROJECTOR)
        val runner = TurnRunner(
            engine,
            ToolRegistry(emptyList()),
            ToolSwitches(context),
            PlanBoard(),
            AskBoard(),
        )

        // The first turn, as the transcript sends it: the question with the file attached.
        val asked = TranscriptEntry(
            id = 1,
            role = ChatRole.USER,
            text = FORM_QUESTION,
            attachments = listOf(stored),
        )
        val firstPasses = mutableListOf<GenerationEvent.Completed>()
        val firstRaw = runner.run(
            conversation = ChatUiState(transcript = listOf(asked)).engineMessages(),
            params = SamplerParams(temperature = 0f, maxTokens = BUDGET, seed = 7),
            mode = AgentMode.AUTO,
            withTools = false,
            notes = ToolNotes(),
            listener = Recording(firstPasses),
        )
        val first = requireNotNull(firstPasses.lastOrNull())
        Log.i(TAG, "first: ${first.stats.row()} :: ${first.content.oneLine()}")
        assertThat(first.reason).isNoneOf(StopReason.ERROR, StopReason.CONTEXT_FULL)
        // One view of the picture plus the instructions, never the tiles.
        val firstTotal = first.stats.promptTokens + first.stats.cachedTokens
        assertThat(firstTotal).isLessThan(ONE_VIEW_CEILING)

        // The follow-up, with the reply back in the transcript exactly as the app keeps it.
        val replied = TranscriptEntry(
            id = 2,
            role = ChatRole.ASSISTANT,
            text = assistantHistoryText(firstRaw, first.stats.thinkingPrefilled),
            answer = parseAssistantReply(firstRaw).answer,
        )
        val followUp = TranscriptEntry(id = 3, role = ChatRole.USER, text = FOLLOW_UP)
        val secondPasses = mutableListOf<GenerationEvent.Completed>()
        runner.run(
            conversation = ChatUiState(
                transcript = listOf(asked, replied, followUp),
            ).engineMessages(),
            params = SamplerParams(temperature = 0f, maxTokens = BUDGET, seed = 7),
            mode = AgentMode.AUTO,
            withTools = false,
            notes = ToolNotes(),
            listener = Recording(secondPasses),
        )
        val second = requireNotNull(secondPasses.lastOrNull())
        Log.i(TAG, "follow-up: ${second.stats.row()} :: ${second.content.oneLine()}")
        // Extended: the picture and the reply were already in the cache, so only the new
        // words were read. A re-read would show the whole conversation as prompt tokens.
        assertThat(second.stats.cachedTokens).isGreaterThan(first.stats.promptTokens)
        assertThat(second.stats.promptTokens).isLessThan(FOLLOW_UP_WORDS)
    }

    private suspend fun storeSource(): MessagePart.File {
        val result = store.store(Uri.fromFile(SOURCE))
        assertThat(result).isInstanceOf(AttachmentResult.Stored::class.java)
        return (result as AttachmentResult.Stored).files.single()
    }

    private fun MessagePart.File.decodedArea(): Long {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        assertThat(bounds.outWidth).isGreaterThan(0)
        return bounds.outWidth.toLong() * bounds.outHeight.toLong()
    }

    private fun io.github.alpharomercoma.openweights.core.engine.GenerationStats.row(): String =
        "prompt=$promptTokens cached=$cachedTokens prefill=${prefillMs}ms decode=${decodeMs}ms"

    private fun String.oneLine(): String = replace('\n', ' ').take(LOG_CHARS)

    private class Recording(private val passes: MutableList<GenerationEvent.Completed>) :
        TurnListener {
        override fun onText(raw: String) = Unit

        override fun onPass(event: GenerationEvent.Completed, raw: String) {
            passes += event
        }

        override fun onSteps(steps: List<AgentStep>) = Unit
        override fun onIntermediate(text: String) = Unit
        override fun onNextPass() = Unit
        override suspend fun onApproval(call: ToolCall): Boolean = false
    }

    private companion object {
        const val TAG = "OpenWeightsImageAttach"
        const val CONTEXT = 8192
        const val BUDGET = 120
        const val LOG_CHARS = 200

        /** A 512-token view with the app's instructions around it; tiles start at 1,800. */
        const val ONE_VIEW_CEILING = 1_200

        /** More prompt tokens than this on a follow-up means the conversation was re-read. */
        const val FOLLOW_UP_WORDS = 80

        val MODEL = File("/data/local/tmp/openweights/vl.gguf")
        val PROJECTOR = File("/data/local/tmp/openweights/mmproj.gguf")
        val SOURCE = File("/data/local/tmp/openweights/img/form-tiles.jpg")

        const val FORM_QUESTION =
            "Read this form. Give the student's name, the course, the contact number, and the " +
                "date the toga must be returned."
        const val FOLLOW_UP = "What is the phone number of Early Marketing at the top?"
    }
}

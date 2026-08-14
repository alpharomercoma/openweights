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
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How much of a turn's prompt has to be read again on each pass.
 *
 * The engine reuses the cached prefix by walking tokens until two differ, so anything that
 * changes the front of a rendered prompt throws away everything behind it. A turn that uses
 * a tool renders three times, and the third render is the one that drops the tool
 * definitions: for a template that puts those near the top, the first difference is near
 * token zero, and the whole conversation is prefilled again at its longest.
 *
 * That is the claim, and it came from two reviewers rather than from a measurement, which is
 * why this exists. `promptTokens` is what the engine actually had to process after reuse, so
 * comparing the three passes says whether the claim is true and what it costs.
 *
 * A measurement, so it asserts only the thing that would make the numbers meaningless: that
 * appending to a conversation is cheaper than starting one.
 */
@RunWith(AndroidJUnit4::class)
class PrefillReuseTest {
    private lateinit var engine: InferenceEngine

    @Before
    fun setUp() {
        assumeTrue("no test model at ${MODEL.path}", MODEL.isFile)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.close() }
    }

    @Test
    fun measuresWhatEachPassOfAToolTurnCostsToPrefill() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))
        assumeTrue("${MODEL.name} renders no tools", engine.loadedModel?.supportsTools == true)

        // Long enough that re-reading it is worth noticing, the way a real turn is by the
        // time a page has been fetched into it.
        val opening = listOf(
            ChatMessage.text(ChatRole.SYSTEM, "You are helpful. " + "Context matters. ".repeat(60)),
            ChatMessage.text(ChatRole.USER, "What is the weather in Manila right now?"),
        )

        val first = prefill(opening, withTools = true, label = "1-cold-with-tools")

        // What the loop appends after a tool runs: the call it made, and what came back.
        val afterTool = opening + listOf(
            ChatMessage.text(ChatRole.ASSISTANT, "Let me look that up."),
            ChatMessage.text(ChatRole.USER, "Results: it is 31 degrees and humid. ".repeat(20)),
        )

        val second = prefill(afterTool, withTools = true, label = "2-appended-with-tools")
        val third = prefill(afterTool, withTools = false, label = "3-same-but-tools-dropped")

        Log.i(TAG, "PREFILL cold=$first appended=$second toolsDropped=$third")

        // The one thing that has to hold for any of it to mean anything.
        assertThat(second).isLessThan(first)
    }

    private suspend fun prefill(
        messages: List<ChatMessage>,
        withTools: Boolean,
        label: String,
    ): Int {
        val completed = engine.chat(
            messages = messages,
            params = SamplerParams(temperature = 0f, maxTokens = 8, seed = 1),
            tools = if (withTools) listOf(SEARCH) else emptyList(),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()
        val tokens = completed.stats.promptTokens
        Log.i(TAG, "PASS $label prefilled=$tokens")
        return tokens
    }

    private companion object {
        const val TAG = "OpenWeightsPrefill"
        const val CONTEXT = 4096

        val SEARCH = ToolDefinition(
            name = "web_search",
            description = "Search the web for current facts.",
            parametersJson = """
                {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
            """.trimIndent(),
        )

        val MODEL = File("/data/local/tmp/openweights/bench/qwen.gguf")
    }
}

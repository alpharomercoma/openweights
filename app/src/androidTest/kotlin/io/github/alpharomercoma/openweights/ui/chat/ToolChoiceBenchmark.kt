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

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import io.github.alpharomercoma.openweights.core.sandbox.Sandbox
import io.github.alpharomercoma.openweights.core.tools.FetchUrlTool
import io.github.alpharomercoma.openweights.core.tools.ReadFileTool
import io.github.alpharomercoma.openweights.core.tools.RunScriptTool
import io.github.alpharomercoma.openweights.core.tools.SearchFilesTool
import io.github.alpharomercoma.openweights.core.tools.SearchSettings
import io.github.alpharomercoma.openweights.core.tools.WebSearchTool
import io.github.alpharomercoma.openweights.core.tools.Workspace
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import io.github.alpharomercoma.openweights.core.tools.WriteFileTool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What four more tools cost the two that already worked.
 *
 * The app carried two tool definitions this morning and carries six now. The literature on
 * tool selection prices a bigger catalogue at anywhere from seven to eighty five percent of
 * accuracy, which is a range wide enough to be useless as a prediction and precise enough to
 * be worth measuring. So this measures it, on the model and the phone that ship.
 *
 * Only the cases that are answerable in both shapes count. Asking whether the model picks
 * `read_file` when `read_file` is not offered measures nothing; the question is whether
 * offering it makes the model worse at the searching it could already do.
 *
 * A measurement rather than an assertion. It asserts only that the run happened, because a
 * threshold on a stochastic decision made by a 1.5B model is a test that fails on Tuesdays
 * for reasons nobody can act on. Read the log, and put the number in docs/CONTEXT.md.
 */
@RunWith(AndroidJUnit4::class)
class ToolChoiceBenchmark {
    private lateinit var engine: InferenceEngine

    /** A question, and the tool a person would agree it calls for. Null means answer it. */
    private data class Case(val prompt: String, val expects: String?)

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
    fun measuresWhatABiggerCatalogueCosts() = runBlocking<Unit> {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))
        assumeTrue(
            "${MODEL.name} renders no tools",
            engine.loadedModel?.supportsTools == true,
        )

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = OkHttpClient()
        val grant = WorkspaceGrant(context)
        val workspace = Workspace(context, grant)
        val web = listOf(
            WebSearchTool(client, SearchSettings()).definition,
            FetchUrlTool(client).definition,
        )
        val everything = web + listOf(
            SearchFilesTool(workspace).definition,
            ReadFileTool(workspace).definition,
            WriteFileTool(workspace).definition,
            RunScriptTool(Sandbox(context), workspace).definition,
        )

        Log.i(TAG, "CATALOGUE two=${web.size} six=${everything.size}")
        val twoScore = score("two", web)
        val sixScore = score("six", everything)
        val outOf = CASES.size * SEEDS.size
        Log.i(TAG, "RESULT two=$twoScore/$outOf six=$sixScore/$outOf")
    }

    /** Runs every case at every seed and reports how many landed on the right answer. */
    private suspend fun score(label: String, tools: List<ToolDefinition>): Int {
        var right = 0
        for (case in CASES) {
            for (seed in SEEDS) {
                val chosen = chosenTool(case.prompt, tools, seed)
                val correct = chosen == case.expects
                if (correct) right++
                Log.i(
                    TAG,
                    "CHOICE catalogue=$label seed=$seed want=${case.expects ?: "none"} " +
                        "got=${chosen ?: "none"} ${if (correct) "hit" else "miss"} " +
                        "prompt=${case.prompt.take(40)}",
                )
            }
        }
        return right
    }

    private suspend fun chosenTool(
        prompt: String,
        tools: List<ToolDefinition>,
        seed: Int,
    ): String? {
        val completed = engine.chat(
            messages = listOf(ChatMessage.text(ChatRole.USER, prompt)),
            params = SamplerParams(temperature = 0.1f, maxTokens = BUDGET, seed = seed),
            tools = tools,
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()
        return completed.toolCalls.firstOrNull()?.name
    }

    private companion object {
        const val TAG = "OpenWeightsChoice"
        const val CONTEXT = 4096
        const val BUDGET = 200

        /** Two, because a third would triple a run that already takes minutes on a phone. */
        val SEEDS = listOf(1, 7)

        /**
         * Only questions that mean the same thing to both catalogues.
         *
         * Every one of these is answerable with the two web tools alone, so a miss in the
         * larger catalogue is the larger catalogue's fault rather than the question's.
         */
        val CASES = listOf(
            Case("What is the weather in Manila right now?", "web_search"),
            Case("Who won the men's final at Wimbledon this year?", "web_search"),
            Case("What is the current population of Tokyo?", "web_search"),
            Case("Read https://example.com and tell me what it says.", "fetch_url"),
            Case("What is the capital of France?", null),
            Case("Who wrote Pride and Prejudice?", null),
        )

        val MODEL = File("/data/local/tmp/openweights/model.gguf")
    }
}

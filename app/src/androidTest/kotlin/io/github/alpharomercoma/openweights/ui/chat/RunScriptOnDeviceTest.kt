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

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import io.github.alpharomercoma.openweights.core.sandbox.Sandbox
import io.github.alpharomercoma.openweights.core.tools.RunScriptTool
import io.github.alpharomercoma.openweights.core.tools.Workspace
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The script tool with a real model on one end and a real interpreter on the other.
 *
 * Everything else about the sandbox is tested by driving it directly, which proves the
 * interpreter runs and proves nothing about whether a 1.5B model can produce a call it will
 * accept. That is the join this covers, and it is the join most likely to be broken.
 *
 * Split in two on purpose. What the tool does with a given script is deterministic and gets
 * a real assertion. What the model decides to write is not, so it gets measured and logged
 * rather than asserted: a threshold on a stochastic choice is a test that fails on Tuesdays.
 */
@RunWith(AndroidJUnit4::class)
class RunScriptOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tool = RunScriptTool(Sandbox(context), Workspace(context, WorkspaceGrant(context)))

    @Test
    fun theToolComputesWhatAScriptSays() = runBlocking {
        // The whole path with the model taken out of it: a call in, an isolated process
        // started, an answer back. Deterministic, so this one is allowed to assert.
        val call = ToolCall(
            id = "1",
            name = "run_script",
            argumentsJson = """{"source":"48273 * 1179"}""",
        )

        val answer = tool.run(call)

        assertThat(answer).contains("56913867")
    }

    @Test
    fun aScriptThatThrowsComesBackAsSomethingToActOn() = runBlocking {
        val call = ToolCall(
            id = "1",
            name = "run_script",
            argumentsJson = """{"source":"nope.missing()"}""",
        )

        val answer = tool.run(call)

        // At roughly a third of generated programs being right, what this says is not a
        // detail: it is what the next attempt has to work from.
        assertThat(answer).contains("did not finish")
        assertThat(answer).contains("nope")
    }

    @Test
    fun measuresWhetherTheModelCanDriveIt() = runBlocking {
        val engine: InferenceEngine = LlamaCppEngine()
        assumeTrue("no test model at ${MODEL.path}", MODEL.isFile)
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))
        assumeTrue("${MODEL.name} renders no tools", engine.loadedModel?.supportsTools == true)

        try {
            SEEDS.forEach { seed -> attempt(engine, seed) }
        } finally {
            engine.close()
        }
    }

    private suspend fun attempt(engine: InferenceEngine, seed: Int) {
        val completed = engine.chat(
            messages = listOf(
                ChatMessage.text(
                    ChatRole.USER,
                    "Work out 48273 multiplied by 1179 by writing and running a script.",
                ),
            ),
            params = SamplerParams(temperature = 0.1f, maxTokens = BUDGET, seed = seed),
            tools = listOf(tool.definition),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()

        val call = completed.toolCalls.firstOrNull()
        if (call == null) {
            Log.i(TAG, "DRIVE seed=$seed called=no said=${completed.content.take(80)}")
            return
        }

        val answer = tool.run(call)
        val right = answer.contains("56913867")
        Log.i(
            TAG,
            "DRIVE seed=$seed called=${call.name} right=$right " +
                "source=${call.argumentsJson.take(90)} answer=${answer.take(60)}",
        )
    }

    private companion object {
        const val TAG = "OpenWeightsScript"
        const val CONTEXT = 4096
        const val BUDGET = 200
        val SEEDS = listOf(1, 7, 13)
        val MODEL = File("/data/local/tmp/openweights/model.gguf")
    }
}

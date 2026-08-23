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
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import io.github.alpharomercoma.openweights.core.sandbox.Sandbox
import io.github.alpharomercoma.openweights.core.tools.FetchUrlTool
import io.github.alpharomercoma.openweights.core.tools.Reachability
import io.github.alpharomercoma.openweights.core.tools.ReadFileTool
import io.github.alpharomercoma.openweights.core.tools.RunScriptTool
import io.github.alpharomercoma.openweights.core.tools.SearchFilesTool
import io.github.alpharomercoma.openweights.core.tools.SearchSettings
import io.github.alpharomercoma.openweights.core.tools.ToolPrompting
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.WebSearchTool
import io.github.alpharomercoma.openweights.core.tools.Workspace
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import io.github.alpharomercoma.openweights.core.tools.WriteFileTool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * What each model actually wrote, as opposed to what anything here could read.
 *
 * [ToolChoiceBenchmark] scores the call that was parsed, which is the right thing to score and
 * the wrong thing to debug with. A row of nulls has two readings that mean opposite things:
 * the model declined, or the model called and neither parser recognised the shape. Hammer 2.1
 * made that concrete. Its template renders tools, so `supportsTools` is true and the prompted
 * parser is switched off by [read]; but the template asks for a bare JSON array rather than
 * the Hermes envelope, so the native parser has nothing to find either. Scored, that is six
 * nulls. It is not necessarily six refusals.
 *
 * So this logs the raw reply, both parsers' verdicts on it, and nothing else. It asserts
 * almost nothing on purpose: it is an instrument, and a run of it is read rather than passed.
 *
 * ```
 * adb shell am instrument -w -e class \
 *   io.github.alpharomercoma.openweights.ui.chat.RawReplyProbe \
 *   io.github.alpharomercoma.openweights.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RawReplyProbe {

    @Test
    fun showsWhatEachModelWrote() = runBlocking<Unit> {
        val present = ToolChoiceBenchmark.MODELS.filter { it.value.isFile }
        assumeTrue("no models under ${ToolChoiceBenchmark.BENCH.path}", present.isNotEmpty())

        for ((name, file) in present) {
            val engine = LlamaCppEngine()
            try {
                engine.load(file, ModelLoadParams(contextLength = ToolChoiceBenchmark.CONTEXT))
                val native = engine.loadedModel?.supportsTools == true
                Log.i(TAG, "PROBE model=$name native=$native")
                for (prompt in PROMPTS) {
                    engine.resetContext()
                    val messages = listOf(
                        ChatMessage.text(ChatRole.SYSTEM, system()),
                        ChatMessage.text(ChatRole.USER, prompt),
                    )
                    val completed = engine.chat(
                        messages,
                        ToolChoiceBenchmark.SHIPPED.copy(
                            maxTokens = ToolChoiceBenchmark.BUDGET,
                            seed = ToolChoiceBenchmark.SEED,
                            temperature = 0f,
                        ),
                        tools = catalogue(),
                    ).toList().filterIsInstance<GenerationEvent.Completed>().single()

                    // Both verdicts, whatever the template said. The point of the probe is the
                    // gap between them, and gating the second one here would hide it.
                    val nativeCall = completed.toolCalls.firstOrNull()?.name
                    val promptedCall = ToolPrompting.parse(completed.content, registry())?.name
                    Log.i(TAG, "ASKED model=$name prompt=${prompt.take(44)}")
                    Log.i(TAG, "  native=$nativeCall prompted=$promptedCall")
                    Log.i(TAG, "  RAW=${completed.content.replace('\n', '⏎').take(RAW)}")
                }
            } catch (failure: Exception) {
                Log.i(TAG, "PROBE model=$name failed=${failure.message}")
            } finally {
                engine.close()
            }
        }
    }

    private fun registry(): ToolRegistry {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = OkHttpClient()
        val workspace = Workspace(context, WorkspaceGrant(context))
        return ToolRegistry(
            listOf(
                WebSearchTool(client, SearchSettings(context), Reachability { true }),
                FetchUrlTool(client, Reachability { true }),
                SearchFilesTool(workspace),
                ReadFileTool(workspace),
                WriteFileTool(workspace),
                RunScriptTool(Sandbox(context), workspace),
            ).filter { it.isAvailable },
        )
    }

    private fun catalogue() = registry().definitions

    private fun system(): String =
        "Today is ${LocalDate.now()}.\n\n${ToolChoiceBenchmark.ANSWER_STYLE}\n\n" +
            ModelPreferences.DEFAULT_TOOL_PROMPT

    private companion object {
        const val TAG = "OpenWeightsRaw"

        /** Enough of the reply to see the shape of a call, short enough to read in logcat. */
        const val RAW = 600

        /** The two the benchmark records as under-calls, and one it records as right. */
        val PROMPTS = listOf(
            "What is the weather in Manila right now?",
            "Read https://example.com and tell me what it says.",
            "What is 48273 times 1179?",
        )
    }
}

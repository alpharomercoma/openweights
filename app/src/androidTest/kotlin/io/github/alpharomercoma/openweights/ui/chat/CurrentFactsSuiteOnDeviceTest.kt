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

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.engine.ExecuTorchEngine
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import io.github.alpharomercoma.openweights.core.engine.NativeExecuTorchBridge
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.AndroidReachability
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.SearchSettings
import io.github.alpharomercoma.openweights.core.tools.SecretSealer
import io.github.alpharomercoma.openweights.core.tools.ToolNotes
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import io.github.alpharomercoma.openweights.core.tools.WebSearchTool
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether a model calls web_search on its own for a question that needs the present, and
 * only then, under the app's instructions and under one wording more.
 *
 * The who-is suite found LFM2.5 1.2B making no call of its own in ninety-six rows on either
 * runtime, "what changed in android 16" included, while Qwen3 called every time. That
 * question does not name anybody, so the app's own search never covers it, and the
 * instructions it was read under open with "You already know the answer to most
 * questions". This suite measures that sentence's other side: ten questions whose answer
 * lives after any training cut (versions, prices, dates, the news), six settled ones the
 * model must answer itself, the instructions as they ship, and the same instructions with
 * one sentence added that names those cases as things it cannot know. Call rate on the
 * ten, over-calling on the six, correctness where a key word can say, time.
 *
 * Every earlier routing wording measured in this codebase moved nothing (three times, per
 * `ModelPreferences.DEFAULT_TOOL_PROMPT`'s notes), which is the reason to measure rather
 * than to ship the sentence. Nothing asserts; a run is read.
 *
 * ```
 * adb shell am instrument -w -r -e class io.github.alpharomercoma.openweights.ui.chat.CurrentFactsSuiteOnDeviceTest \
 *   [-e models a.pte,b.gguf] [-e arms shipped,explicit] \
 *   io.github.alpharomercoma.openweights.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class CurrentFactsSuiteOnDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun currentFactsAcrossModelsAndInstructions(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val wanted = arguments.getString("models")?.split(',')?.map { it.trim() } ?: MODELS
        val arms = arguments.getString("arms")?.split(',')?.map { it.trim() } ?: ARMS
        val models = wanted.map { EVAL_DIR.resolve(it) }.filter { it.isFile }
        val dump = EVAL_DIR.resolve("prompt_dump.json")
        assumeTrue(
            "no models or prompt_dump.json under $EVAL_DIR",
            models.isNotEmpty() && dump.isFile,
        )
        val shipped = org.json.JSONObject(dump.readText()).getString("system")
        val explicit = if (ANCHOR in shipped) {
            shipped.replace(ANCHOR, "$ANCHOR $EXPLICIT")
        } else {
            "$shipped\n\n$EXPLICIT"
        }

        val search = WebSearchTool(
            OkHttpClient(),
            SearchSettings(context, SecretSealer.Unavailable),
            AndroidReachability(context),
        )
        Log.i(TAG, "START models=${models.map { it.name }} arms=$arms cases=${CASES.size}")

        for (model in models) {
            val engine = engineFor(model)
            try {
                engine.load(model, ModelLoadParams(contextLength = CONTEXT))
                for (arm in arms) {
                    val system = if (arm == "explicit") explicit else shipped
                    val runner = TurnRunner(
                        engine,
                        ToolRegistry(listOf(search)),
                        ToolSwitches(context),
                        PlanBoard(),
                        AskBoard(),
                    )
                    for (case in CASES) {
                        val listener = Recording()
                        val started = System.currentTimeMillis()
                        val raw = runCatching {
                            runner.run(
                                conversation = listOf(
                                    ChatMessage.text(ChatRole.SYSTEM, system),
                                    ChatMessage.text(ChatRole.USER, case.question),
                                ),
                                params = PARAMS,
                                mode = AgentMode.AUTO,
                                withTools = true,
                                notes = ToolNotes(),
                                listener = listener,
                                question = case.question,
                            )
                        }.getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message}" }
                        val ms = System.currentTimeMillis() - started
                        val answer = parseAssistantReply(raw).answer.trim()
                        val calls = listener.steps.filterIsInstance<AgentStep.Ran>().size
                        val correct = case.keys.takeIf { it.isNotEmpty() }
                            ?.any { answer.contains(it, ignoreCase = true) }
                        Log.i(
                            TAG,
                            "ROW model=${model.name} arm=$arm case=${case.id} ms=$ms " +
                                "passes=${listener.passes.size} calls=$calls " +
                                "wants=${case.wantsSearch} chars=${answer.length} " +
                                "correct=$correct " +
                                "over=${!case.wantsSearch && calls > 0} " +
                                "missed=${case.wantsSearch && calls == 0}",
                        )
                        Log.i(TAG, "  REPLY case=${case.id} arm=$arm ${answer.oneLine()}")
                        engine.resetContext()
                    }
                }
            } finally {
                engine.unload()
            }
        }
        Log.i(TAG, "END")
    }

    private fun engineFor(model: File): InferenceEngine = if (model.extension == "pte") {
        ExecuTorchEngine(NativeExecuTorchBridge(), temperature = 0f)
    } else {
        LlamaCppEngine()
    }

    private fun String.oneLine(): String = replace("\n", "\\n").take(400)

    private class Recording : TurnListener {
        val passes = mutableListOf<GenerationEvent.Completed>()
        val steps = mutableListOf<AgentStep>()

        override fun onText(raw: String) = Unit

        override fun onPass(event: GenerationEvent.Completed, raw: String) {
            passes += event
        }

        override fun onSteps(steps: List<AgentStep>) {
            this.steps += steps
        }

        override fun onIntermediate(text: String) = Unit
        override fun onNextPass() = Unit
        override suspend fun onApproval(call: ToolCall): Boolean = true
    }

    private class Case(
        val id: String,
        val question: String,
        val wantsSearch: Boolean,
        val keys: List<String>,
    )

    private companion object {
        const val TAG = "CurrentFacts"
        const val CONTEXT = 4096
        val EVAL_DIR = File("/data/local/tmp/openweights/eval")

        val MODELS = listOf(
            "LFM2.5-1.2B-Instruct-8da4w-32k.pte",
            "Qwen3-1.7B-Q8_0.gguf",
            "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
        )
        val ARMS = listOf("shipped", "explicit")

        val PARAMS = SamplerParams(
            temperature = 0f,
            topK = 1,
            seed = 1,
            maxTokens = 600,
            thinking = false,
        )

        /** The sentence in the shipped instructions the added one follows. */
        const val ANCHOR = "information that changed after your training."

        /** The one sentence under test. */
        const val EXPLICIT = "Whether something is out yet, what changed in a product, what " +
            "the current version, price, date or weather is, and what the news says are all " +
            "things that changed after your training: call web_search for those before " +
            "answering, every time, and never answer them from memory."

        val CASES = listOf(
            Case("android16", "what changed in android 16", true, listOf("Android")),
            Case("kotlin", "what is the latest version of kotlin", true, listOf("2.")),
            Case("bitcoin", "how much is bitcoin right now", true, listOf("$", "USD")),
            Case("iphone", "when is the next iphone event", true, listOf("Apple", "September")),
            Case("news", "what is in the news today", true, emptyList()),
            Case("executorch", "what is the current version of executorch", true, listOf("1.")),
            Case("python", "is python 3.14 out yet", true, listOf("3.14")),
            Case("weather", "what is the weather in manila today", true, emptyList()),
            Case("langchain", "what did langchain release this month", true, listOf("LangChain")),
            Case(
                "ps6",
                "has sony announced the playstation 6",
                true,
                listOf("PlayStation", "Sony"),
            ),
            Case("peru", "what is the capital of peru", false, listOf("Lima")),
            Case("translate", "translate thank you to japanese", false, listOf("arigat", "ありがとう")),
            Case("math", "what is 17 * 23", false, listOf("391")),
            Case("limerick", "write a limerick about cats", false, listOf("cat")),
            Case("hashmap", "explain how a hash map works", false, listOf("hash")),
            Case("austen", "who wrote pride and prejudice", false, listOf("Austen")),
        )
    }
}

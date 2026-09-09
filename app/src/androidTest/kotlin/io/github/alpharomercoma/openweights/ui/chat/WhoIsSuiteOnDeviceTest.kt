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
import io.github.alpharomercoma.openweights.core.tools.WebSearchFraming
import io.github.alpharomercoma.openweights.core.tools.WebSearchTool
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Who is X" through the whole loop on the phone: real model, real search, the app's own
 * instructions, and an answer key written before the run.
 *
 * The question the suite answers is whether the app's handling of a question that names
 * somebody generalises past the one case it was built on. Arms, so that each thing the
 * app does can be priced against the same model in the same hour: `shipped` (the search
 * made before the first pass, the framing that ships), `before` (the note on the question
 * alone, which is what shipped until 2026-09-10), and one arm per result framing
 * (`sources`, `plain`, `direct`; see WebSearchFraming). Three models: the compiled
 * LFM2.5 1.2B the shortlist leads with, and the two GGUF files the routing work used.
 * The numbers every arm produced are in `docs/research/who-is-questions.md`.
 *
 * Sixteen cases: ten that name somebody or something (famous, niche, fictional, an
 * office, an organisation), and six that must not be searched (arithmetic, a pronoun, the
 * assistant itself, the user's family, a haiku) plus one control that the model itself
 * should call the tool on, which separates a runtime that cannot call from a model that
 * chose not to. Each row records the time, the passes, whether the model called, whether
 * the app searched, the length of the reply, whether the answer key's words appear, and
 * the reply itself, so the quality can be read and not only counted.
 *
 * Nothing asserts. It is an instrument; a run is read.
 *
 * ```
 * adb shell am instrument -w -r -e class io.github.alpharomercoma.openweights.ui.chat.WhoIsSuiteOnDeviceTest \
 *   [-e models a.pte,b.gguf] [-e arms shipped,before] \
 *   io.github.alpharomercoma.openweights.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class WhoIsSuiteOnDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun whoIsAcrossModelsAndArms(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val wanted = arguments.getString("models")?.split(',')?.map { it.trim() } ?: MODELS
        val arms = arguments.getString("arms")?.split(',')?.map { it.trim() } ?: ARMS
        val models = wanted.map { EVAL_DIR.resolve(it) }.filter { it.isFile }
        val dump = EVAL_DIR.resolve("prompt_dump.json")
        assumeTrue(
            "no models or prompt_dump.json under $EVAL_DIR",
            models.isNotEmpty() && dump.isFile,
        )
        val system = org.json.JSONObject(dump.readText()).getString("system")

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
                    val runner = TurnRunner(
                        engine,
                        ToolRegistry(listOf(search)),
                        ToolSwitches(context),
                        PlanBoard(),
                        AskBoard(),
                    ).apply {
                        notesSubject = arm != "nonote"
                        searchesForSubject = arm != "before"
                    }
                    // The result framing under test, where the arm names one; see
                    // WebSearchFraming for what each wording measured.
                    WebSearchFraming.current = when (arm) {
                        "sources" -> WebSearchFraming.SOURCES
                        "plain" -> WebSearchFraming.PLAIN
                        else -> WebSearchFraming.DIRECT
                    }
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
                        val ran = listener.steps.filterIsInstance<AgentStep.Ran>()
                        val hostSearched = ran.any { it.call.id == HOST_SEARCH_ID }
                        val modelCalls = ran.count { it.call.id != HOST_SEARCH_ID }
                        val searched = hostSearched || modelCalls > 0
                        val correct = case.keys.takeIf { it.isNotEmpty() }
                            ?.any { answer.contains(it, ignoreCase = true) }
                        val unwanted = case.wantsSearch == false && searched
                        val missed = case.wantsSearch == true && !searched
                        Log.i(
                            TAG,
                            "ROW model=${model.name} arm=$arm case=${case.id} ms=$ms " +
                                "passes=${listener.passes.size} modelCalls=$modelCalls " +
                                "hostSearch=$hostSearched chars=${answer.length} " +
                                "lines=${answer.lines().count { it.isNotBlank() }} " +
                                "correct=$correct unwantedSearch=$unwanted missedSearch=$missed",
                        )
                        Log.i(TAG, "  REPLY case=${case.id} arm=$arm ${answer.oneLine()}")
                        // What the model was given, so a wrong answer can be blamed on the
                        // results or on the reading of them.
                        ran.forEach { step ->
                            Log.i(TAG, "  RESULT case=${case.id} arm=$arm ${step.result.oneLine()}")
                        }
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

    private fun String.oneLine(): String = replace("\n", "\\n").take(600)

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

    /**
     * @param wantsSearch true when the answer cannot be known without one, false when a
     *   search would be wrong, null when either is acceptable (a famous name).
     * @param keys words a correct answer contains, any one of them; empty when correctness
     *   is not a matter of words (a niche name where the honest answer is a hedge).
     */
    private class Case(
        val id: String,
        val question: String,
        val wantsSearch: Boolean?,
        val keys: List<String>,
    )

    private companion object {
        const val TAG = "WhoIsSuite"
        const val CONTEXT = 4096
        const val HOST_SEARCH_ID = "named-subject"
        val EVAL_DIR = File("/data/local/tmp/openweights/eval")

        val MODELS = listOf(
            "LFM2.5-1.2B-Instruct-8da4w-32k.pte",
            "Qwen3-1.7B-Q8_0.gguf",
            "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
        )

        // `shipped` searches first and carries no note; `before` is the note alone, the loop
        // as it was until 2026-09-10. A third arm, the search made after a wasted first
        // pass, was measured on the way here and is in the research note.
        val ARMS = listOf("shipped", "before")

        // Greedy and thinking off, as the routing work and the public benchmarks ran.
        val PARAMS =
            SamplerParams(temperature = 0f, topK = 1, seed = 1, maxTokens = 600, thinking = false)

        val CASES = listOf(
            Case(
                "alpha",
                "who is alpha romer coma",
                true,
                listOf("engineer", "OpenWeights", "developer", "machine learning"),
            ),
            // The series by name: "Hunter" alone let "friend of Gon Freecss" pass a reply
            // that placed him in Genshin Impact.
            Case(
                "killua",
                "who is killua zoldyck",
                true,
                listOf("Hunter x Hunter", "Hunter × Hunter"),
            ),
            Case("swift", "who is taylor swift", null, listOf("singer", "songwriter", "musician")),
            Case("einstein", "who is albert einstein", null, listOf("physic", "relativity")),
            Case("runkle", "who is sydney runkle", true, listOf("LangChain")),
            Case("president", "who is the president of the philippines", true, listOf("Marcos")),
            Case("nadella", "who is satya nadella", null, listOf("Microsoft")),
            Case("liquid", "tell me about liquid ai", true, listOf("Liquid", "LFM", "foundation")),
            Case("nvidia", "who is the ceo of nvidia", true, listOf("Huang", "Jensen")),
            Case("lacap", "who is xynil jhed lacap", true, emptyList()),
            Case("math", "what is 2 + 2", false, listOf("4", "four")),
            Case("she", "who is she", false, listOf("?", "who", "which", "context", "mean")),
            Case(
                "yourself",
                "tell me about yourself",
                false,
                listOf("assistant", "model", "help", "AI"),
            ),
            Case(
                "mother",
                "who is my mother",
                false,
                listOf("know", "?", "tell", "cannot", "can't"),
            ),
            Case("android", "what changed in android 16", null, listOf("Android")),
            Case("haiku", "write a haiku about rain", false, listOf("rain")),
        )
    }
}

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
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
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
import io.github.alpharomercoma.openweights.core.tools.Tool
import io.github.alpharomercoma.openweights.core.tools.ToolNotes
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import io.github.alpharomercoma.openweights.core.tools.WebSearchTool
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Whether a model searches when it must and only then, measured on public sets through
 * the app's own loop, on whatever phone, runtime, quant and tool set it is given.
 *
 * The rows are `tools/eval/bench/decisions.json`: a seeded sample of RetrievalQA, PopQA
 * and FreshQA, each row carrying the set's own label for whether the answer lives in the
 * weights (`need`), and the set's own answer aliases. None of them was written here. The
 * three hand-written suites this replaces asked the maintainer's own questions and were
 * tuned on them, which is the thing a benchmark exists not to be.
 *
 * Every row goes through the real `TurnRunner`, the real `WebSearchTool`, the app's
 * shipped instructions from `prompt_dump.json` and the app's own date exchange, greedy,
 * thinking off. What varies is an arm, and an arm is only what the app itself can vary:
 *
 * - `bare`: no tools at all. What the weights answer alone; the utility baseline.
 * - `driven-search`: the model decides, holding web_search only, as a fresh install does.
 * - `driven-full`: the model decides, holding the whole catalogue as every measurement
 *   before 2026-09-08 ran (fifteen stubs beside the real search, from the dump's own
 *   definitions, so the prompt bytes are the ones that catalogue produced).
 * - `intent-search`, `intent-full`: the same, with the search the app makes when the
 *   model announced one, claimed one or lamented and called nothing (`honoursIntent`).
 *
 * `driven-search` against `driven-full` on the same model is the maintainer's hypothesis
 * that the tool-list change caused the under-calling; the same pair on the compiled and
 * the GGUF form of one model is the runtime question. Both reviewers asked for exactly
 * that 2 x 2 before anything else, and it is the default.
 *
 * One JSON line per row, appended under the app's external files (`eval-results/`, where
 * the public benchmarks write too: an app cannot write under /data/local/tmp), so a run that dies resumes
 * where it stopped and a phone that is slow can be given fewer rows. Nothing asserts;
 * `tools/eval/bench/grade_decisions.py` reads the files and writes the tables.
 *
 * ```
 * adb shell am instrument -w -r -e class io.github.alpharomercoma.openweights.ui.chat.DecisionSuiteOnDeviceTest#decisions \
 *   [-e models a.pte,b.gguf] [-e arms driven-search,driven-full] [-e sets retrievalqa,popqa] [-e from 40] [-e rows 40] \
 *   io.github.alpharomercoma.openweights.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DecisionSuiteOnDeviceTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    /**
     * The test package's own context for the switches, so no default is read from, and
     * nothing is written to, the preferences of the app somebody uses on this phone.
     */
    private val own = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun decisions(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val wanted = arguments.getString("models")?.split(',')?.map { it.trim() } ?: MODELS
        val arms = arguments.getString("arms")?.split(',')?.map { it.trim() } ?: ARMS
        val sets = arguments.getString("sets")?.split(',')?.map { it.trim() }?.toSet()
        val limit = arguments.getString("rows")?.toIntOrNull() ?: Int.MAX_VALUE
        // A lab phone gets forty-five minutes a run, so the rows are cut into chunks:
        // `from` skips the first N of the filtered rows and `rows` caps what follows.
        val from = arguments.getString("from")?.toIntOrNull() ?: 0
        val models = wanted.map { EVAL_DIR.resolve(it) }.filter { it.isFile }
        val dump = EVAL_DIR.resolve("prompt_dump.json")
        val bench = EVAL_DIR.resolve("decisions.json")
        assumeTrue(
            "needs models, prompt_dump.json and decisions.json under $EVAL_DIR",
            models.isNotEmpty() && dump.isFile && bench.isFile,
        )
        val prompt = JSONObject(dump.readText())
        val system = prompt.getString("system")
        val rows = JSONObject(bench.readText()).getJSONArray("rows").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
        }.filter { sets == null || it.getString("set") in sets }.drop(from).take(limit)

        val search = WebSearchTool(
            OkHttpClient(),
            SearchSettings(app, SecretSealer.Unavailable),
            AndroidReachability(app),
        )
        val catalogue = stubs(prompt.getJSONArray("tools"), except = search.definition.name)
        val results = resultsDir()
        Log.i(TAG, "START models=${models.map { it.name }} arms=$arms rows=${rows.size}")

        for (model in models) {
            val engine = engineFor(model)
            try {
                engine.load(model, ModelLoadParams(contextLength = CONTEXT))
                for (arm in arms) {
                    val out = results.resolve("decisions-${model.nameWithoutExtension}-$arm.jsonl")
                    val done = if (out.isFile) {
                        out.readLines().mapNotNull {
                            runCatching { JSONObject(it).optString("id") }.getOrNull()
                        }.toSet()
                    } else {
                        emptySet()
                    }
                    val tools = if (arm.endsWith(
                            "-full",
                        )
                    ) {
                        listOf(search) + catalogue
                    } else {
                        listOf(search)
                    }
                    val runner = TurnRunner(
                        engine,
                        ToolRegistry(tools),
                        ToolSwitches(own),
                        PlanBoard(),
                        AskBoard(),
                    )
                        .apply {
                            // `driven-*` is the loop as it shipped until 2026-09-10 (note
                            // and pushes only); `intent-*` adds the search the app makes
                            // when the model says it will search, says it did, or says
                            // it does not know, and calls nothing.
                            honoursIntent = arm.startsWith("intent-") || arm.startsWith("doubt-")
                            // `doubt-*` adds the search the app makes when the model's own
                            // token probabilities put an answer among its least confident.
                            honoursDoubt = arm.startsWith("doubt-")
                        }
                    val withTools = arm != "bare"
                    header(
                        out,
                        model,
                        arm,
                        if (withTools) tools else emptyList(),
                        system,
                        rows.size,
                    )
                    for (row in rows) {
                        val id = row.getString("id")
                        if (id in done) continue
                        val question = row.getString("question")
                        val listener = Recording()
                        val pssBefore = Debug.getPss()
                        val started = System.currentTimeMillis()
                        val raw = runCatching {
                            runner.run(
                                conversation = listOf(ChatMessage.text(ChatRole.SYSTEM, system)) +
                                    PromptDay.exchange() +
                                    ChatMessage.text(ChatRole.USER, question),
                                params = PARAMS,
                                mode = AgentMode.AUTO,
                                withTools = withTools,
                                notes = ToolNotes(),
                                listener = listener,
                                question = question,
                            )
                        }.getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message}" }
                        val ms = System.currentTimeMillis() - started
                        val record =
                            record(
                                row,
                                model,
                                arm,
                                if (withTools) tools.size else 0,
                                raw,
                                ms,
                                listener,
                                pssBefore,
                                runner.lastConfidence,
                            )
                        out.appendText(record.toString() + "\n")
                        Log.i(
                            TAG,
                            "ROW model=${model.name} arm=$arm id=$id ms=$ms " +
                                "passes=${listener.passes.size} calls=${record.getJSONArray(
                                    "calls",
                                ).length()} " +
                                "chars=${record.getInt("chars")}",
                        )
                        engine.resetContext()
                    }
                }
            } finally {
                engine.unload()
            }
        }
        Log.i(TAG, "END")
    }

    /**
     * Whether a reply that should recite something recites it, or quotes the instructions
     * back instead.
     *
     * From a screenshot of 2026-09-10: "recite the national anthem of the Philippines"
     * answered with "The complete answer must be provided directly... Since I already know
     * the information..." and no anthem. Both reviewers named the same two suspects and
     * the same one measurement to tell them apart: instruction echo under greedy decoding,
     * which reproduces distinctive phrases from the system prompt and stops when the
     * prompt is empty; or a refusal to reproduce a text, which produces refusal language
     * under either prompt. Six public-domain recitations, each under the shipped
     * instructions and under none, each with the search on offer and without; the grader
     * measures the longest run of words each reply shares with the instructions.
     */
    @Test
    fun instructionEcho(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val wanted = arguments.getString("models")?.split(',')?.map { it.trim() } ?: MODELS
        val models = wanted.map { EVAL_DIR.resolve(it) }.filter { it.isFile }
        val dump = EVAL_DIR.resolve("prompt_dump.json")
        assumeTrue(
            "needs models and prompt_dump.json under $EVAL_DIR",
            models.isNotEmpty() && dump.isFile,
        )
        val system = JSONObject(dump.readText()).getString("system")
        val results = resultsDir()
        val search = WebSearchTool(
            OkHttpClient(),
            SearchSettings(app, SecretSealer.Unavailable),
            AndroidReachability(app),
        )
        for (model in models) {
            val engine = engineFor(model)
            try {
                engine.load(model, ModelLoadParams(contextLength = CONTEXT))
                val out = results.resolve("echo-${model.nameWithoutExtension}.jsonl")
                val runner = TurnRunner(
                    engine,
                    ToolRegistry(listOf(search)),
                    ToolSwitches(own),
                    PlanBoard(),
                    AskBoard(),
                )
                    .apply {
                        honoursIntent = false
                        honoursDoubt = false
                    }
                for ((id, question) in RECITATIONS) {
                    // With and without the instructions, and with and without the search on
                    // offer: the first run of this probe, instructions only, found no echo in
                    // thirty-six replies, and the screenshot that started it had web search
                    // switched on, so the tool block in the prompt is the other suspect.
                    for (instructions in listOf("shipped", "none")) {
                        for (withTools in listOf(false, true)) {
                            val listener = Recording()
                            val started = System.currentTimeMillis()
                            val conversation = buildList {
                                if (instructions ==
                                    "shipped"
                                ) {
                                    add(ChatMessage.text(ChatRole.SYSTEM, system))
                                }
                                addAll(PromptDay.exchange())
                                add(ChatMessage.text(ChatRole.USER, question))
                            }
                            val raw = runCatching {
                                runner.run(
                                    conversation,
                                    PARAMS,
                                    AgentMode.AUTO,
                                    withTools,
                                    ToolNotes(),
                                    listener,
                                    question = question,
                                )
                            }.getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message}" }
                            val answer = parseAssistantReply(raw).answer.trim()
                            val calls = listener.steps.filterIsInstance<AgentStep.Ran>().map {
                                it.call.name
                            }
                            out.appendText(
                                JSONObject()
                                    .put(
                                        "id",
                                        id,
                                    ).put("instructions", instructions).put("tools", withTools)
                                    .put("question", question)
                                    .put("model", model.name).put("runtime", engine.runtimeName())
                                    .put("ms", System.currentTimeMillis() - started)
                                    .put(
                                        "passes",
                                        listener.passes.size,
                                    ).put("calls", JSONArray(calls))
                                    .put(
                                        "chars",
                                        answer.length,
                                    ).put("answer", answer).put("raw", raw)
                                    .put("system_sha1", sha1(system))
                                    .toString() + "\n",
                            )
                            Log.i(
                                TAG,
                                "ECHO model=${model.name} id=$id instructions=$instructions tools=$withTools calls=${calls.size} chars=${answer.length}",
                            )
                            engine.resetContext()
                        }
                    }
                }
            } finally {
                engine.unload()
            }
        }
    }

    private fun header(
        out: File,
        model: File,
        arm: String,
        tools: List<Tool>,
        system: String,
        rows: Int,
    ) {
        if (out.isFile && out.length() > 0) return
        val power = app.getSystemService<PowerManager>()
        val battery = app.getSystemService<BatteryManager>()
        out.appendText(
            JSONObject()
                .put("header", true)
                .put("model", model.name).put("arm", arm).put("rows", rows)
                .put("runtime", if (model.extension == "pte") "executorch" else "llama.cpp")
                .put("quant", quantOf(model.name))
                .put("tools", JSONArray(tools.map { it.definition.name }))
                .put(
                    "tools_sha1",
                    sha1(
                        tools.joinToString("\n") {
                            it.definition.description +
                                it.definition.parametersJson
                        },
                    ),
                )
                .put("system", system).put("system_sha1", sha1(system))
                .put("date_exchange", JSONArray(PromptDay.exchange().map { it.text }))
                .put("context", CONTEXT)
                .put(
                    "sampler",
                    JSONObject().put(
                        "temperature",
                        PARAMS.temperature,
                    ).put(
                        "top_k",
                        PARAMS.topK,
                    ).put("seed", PARAMS.seed).put("max_tokens", PARAMS.maxTokens),
                )
                .put(
                    "device",
                    Build.MODEL,
                ).put("soc", Build.SOC_MODEL).put("hardware", Build.HARDWARE)
                .put(
                    "android",
                    Build.VERSION.RELEASE,
                ).put("cores", Runtime.getRuntime().availableProcessors())
                .put("thermal", power?.currentThermalStatus ?: -1)
                .put(
                    "battery_pct",
                    battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1,
                )
                .put("charging", battery?.isCharging ?: false)
                .put("started", System.currentTimeMillis())
                .toString() + "\n",
        )
    }

    @Suppress("LongParameterList")
    private fun record(
        row: JSONObject,
        model: File,
        arm: String,
        toolCount: Int,
        raw: String,
        ms: Long,
        listener: Recording,
        pssBefore: Long,
        confidence: Float?,
    ): JSONObject {
        val answer = parseAssistantReply(raw).answer.trim()
        val ran = listener.steps.filterIsInstance<AgentStep.Ran>()
        val calls = JSONArray(
            ran.map { step ->
                JSONObject()
                    .put("name", step.call.name)
                    .put("host", step.call.id == HOST_SEARCH_ID)
                    .put("arguments", step.call.argumentsJson)
                    .put("successful", step.successful)
                    .put("ms", step.millis)
                    .put("result", step.result.take(RESULT_CHARS))
            },
        )
        val skipped = listener.steps.filterIsInstance<AgentStep.Skipped>().map { it.call.name }
        val passes = JSONArray(
            listener.passes.map { pass ->
                JSONObject()
                    .put("stop", pass.reason.name)
                    .put("prompt_tokens", pass.stats.promptTokens)
                    .put("generated_tokens", pass.stats.generatedTokens)
                    .put("prefill_ms", pass.stats.prefillMs)
                    .put("decode_ms", pass.stats.decodeMs)
                    .put("ttft_ms", pass.stats.timeToFirstTokenMs)
                    .put("context_used", pass.stats.contextUsed)
                    .put("asked", JSONArray(pass.toolCalls.map { it.name }))
            },
        )
        val power = app.getSystemService<PowerManager>()
        return JSONObject()
            .put("id", row.getString("id")).put("set", row.getString("set"))
            .put("stratum", row.getString("stratum"))
            .put("need", if (row.isNull("need")) JSONObject.NULL else row.getBoolean("need"))
            .put("stale_risk", row.optBoolean("stale_risk"))
            .put("false_premise", row.optBoolean("false_premise"))
            .put("question", row.getString("question"))
            .put("answers", row.getJSONArray("answers"))
            .put("model", model.name).put("arm", arm).put("tool_count", toolCount)
            .put("ms", ms).put("chars", answer.length)
            .put("passes", passes).put("calls", calls).put("declined", JSONArray(skipped))
            .put("confidence", confidence ?: JSONObject.NULL)
            .put("answer", answer).put("raw", raw)
            .put("pss_kb_before", pssBefore).put("pss_kb_after", Debug.getPss())
            .put("thermal", power?.currentThermalStatus ?: -1)
    }

    /**
     * The catalogue as the prompt dump recorded it, minus the one tool that is real.
     *
     * A stub describes itself with the dump's own description and schema, so the tool
     * block the model reads is byte for byte the one the dump's catalogue produced; what a
     * stub does when called is say so. It is not user-facing so that it answers to no
     * switch: the point is that it is offered.
     */
    private fun stubs(definitions: JSONArray, except: String): List<Tool> =
        (0 until definitions.length()).map { definitions.getJSONObject(it) }
            .filter { it.getString("name") != except }
            .map { spec ->
                object : Tool {
                    override val definition = ToolDefinition(
                        name = spec.getString("name"),
                        description = spec.getString("description"),
                        parametersJson = spec.getJSONObject("parameters").toString(),
                    )
                    override val isUserFacing: Boolean = false
                    override suspend fun run(call: ToolCall): String =
                        "This tool is not available during this evaluation. Answer from what you have."
                }
            }

    /** Where the rows go; the same directory the public benchmarks write, pulled the same way. */
    private fun resultsDir(): File =
        app.getExternalFilesDir(null)!!.resolve("eval-results").apply { mkdirs() }

    private fun engineFor(model: File): InferenceEngine = if (model.extension == "pte") {
        ExecuTorchEngine(NativeExecuTorchBridge(), temperature = 0f)
    } else {
        LlamaCppEngine()
    }

    private fun InferenceEngine.runtimeName(): String =
        if (this is ExecuTorchEngine) "executorch" else "llama.cpp"

    private fun quantOf(name: String): String = when {
        "8da4w" in name -> "8da4w"
        "INT8-INT4" in name -> "int8-int4"
        else -> Regex("Q\\d[_A-Z0-9]*").find(name)?.value ?: "unknown"
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

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

    private companion object {
        const val TAG = "DecisionSuite"
        const val CONTEXT = 4096

        // Whole search results, so the grader's "answer in results" counts every hit the
        // model saw: at 1,500 the fourth and fifth of five hits were cut from the record
        // (measured 2026-09-10, the five-result run), and findability read low.
        const val RESULT_CHARS = 8000
        const val HOST_SEARCH_ID = "app-search"
        val EVAL_DIR = File("/data/local/tmp/openweights/eval")

        val MODELS = listOf(
            "LFM2.5-1.2B-Instruct-8da4w-32k.pte",
            "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            "Qwen3-1.7B-Q8_0.gguf",
        )

        // The 2 x 2 the reviewers asked for first, then the fix, then the baseline.
        val ARMS = listOf("driven-search", "driven-full", "intent-search", "intent-full", "bare")

        // Greedy and thinking off, as the routing work and the public benchmarks ran.
        val PARAMS =
            SamplerParams(temperature = 0f, topK = 1, seed = 1, maxTokens = 600, thinking = false)

        /** Public-domain texts a model of this size has seen many times. */
        val RECITATIONS = listOf(
            "anthem-ph" to "recite the national anthem of the Philippines",
            "anthem-us" to "recite the first verse of The Star-Spangled Banner",
            "lamb" to "recite Mary Had a Little Lamb",
            "gettysburg" to "recite the opening sentence of the Gettysburg Address",
            "pi" to "recite pi to 20 decimal places",
            "alphabet" to "recite the alphabet backwards",
        )
    }
}

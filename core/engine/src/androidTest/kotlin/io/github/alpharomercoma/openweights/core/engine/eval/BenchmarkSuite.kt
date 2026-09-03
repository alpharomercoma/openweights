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

package io.github.alpharomercoma.openweights.core.engine.eval

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The public-benchmark suite: GSM8K, IFEval and BFCL prompts, against any engine.
 *
 * The prompts are the seeded subset in `tools/eval/bench/benchmarks.json`, pulled from
 * the published datasets and pushed beside the models; nothing here is written by us.
 * The device only answers. Grading is the host's job (`tools/eval/bench/grade.py`),
 * because IFEval's checker and BFCL's answer matching are their own code and a phone
 * should not carry a second copy of either. What lands here is every raw reply, the
 * parsed tool calls, and the timings, greedy, so a rerun reproduces.
 *
 * A class is time-boxed. Test Lab kills a physical-device run at 45 minutes and keeps
 * nothing it wrote, so the suite stops itself before that and writes what it has; a
 * report says how many prompts it reached, and a rerun with `sets` narrows to the rest.
 */
object BenchmarkSuite {

    class Options(
        /** Substring of the model file name to run; empty runs every pushed model. */
        val model: String = "",
        /** Benchmark sets to run, e.g. gsm8k,ifeval,bfcl; empty runs all. */
        val sets: Set<String> = emptySet(),
        /** At most this many prompts per set; 0 is all of them. */
        val limit: Int = 0,
        /** Prompts to skip at the start of each set: the rerun of a time-boxed class. */
        val skip: Int = 0,
        /** Minutes the whole class may use before it stops and writes. */
        val budgetMinutes: Int = DEFAULT_BUDGET_MINUTES,
        /** Repeat penalty for the engines that have one; null keeps the app's default. */
        val repeatPenalty: Float? = null,
    ) {
        init {
            // Skip counts prompts within each set, so a rerun of a cut class names its set.
            require(skip == 0 || sets.size == 1) { "skip needs exactly one set" }
        }
    }

    /** Families whose format has no tool syntax; BFCL is recorded as skipped for them. */
    private val TOOLLESS = listOf("smollm2", "gemma")

    @Volatile
    private var deadline = Long.MAX_VALUE
    private var engineName = "unknown"

    fun startClock(options: Options) {
        deadline = SystemClock.elapsedRealtime() + options.budgetMinutes * MS_PER_MINUTE
    }

    private fun timeLeft(): Boolean = SystemClock.elapsedRealtime() < deadline

    suspend fun run(
        engine: InferenceEngine,
        modelName: String,
        promptsFile: File,
        resultsDir: File,
        options: Options,
    ): File {
        val toolless = TOOLLESS.any { it in modelName.lowercase().filter(Char::isLetterOrDigit) }
        engineName = engine::class.simpleName ?: "unknown"
        val doc = JSONObject(promptsFile.readText())
        val all = doc.getJSONArray("prompts")
        val perSet = mutableMapOf<String, Int>()
        val results = JSONArray()
        var exhausted = false

        for (i in 0 until all.length()) {
            val p = all.getJSONObject(i)
            val set = p.getString("set")
            if (options.sets.isNotEmpty() && set !in options.sets) continue
            val seen = perSet.getOrDefault(set, 0)
            perSet[set] = seen + 1
            if (seen < options.skip) continue
            if (options.limit > 0 && seen - options.skip >= options.limit) continue
            val id = p.getString("id")
            if (set == "bfcl" && toolless) {
                results.put(
                    JSONObject().put("id", id).put("set", set).put("status", "skipped")
                        .put("reason", "family has no tool syntax"),
                )
                continue
            }
            if (!timeLeft()) {
                exhausted = true
                break
            }
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching {
                turn(engine, p.getString("prompt"), p.getInt("max_tokens"), tools(p), options)
            }
            results.put(
                outcome.fold(
                    onSuccess = { record(id, set, it) },
                    onFailure = {
                        JSONObject().put("id", id).put("set", set).put("status", "error")
                            .put("error", it.toString())
                    },
                ).put("wall_ms", SystemClock.elapsedRealtime() - started),
            )
            Log.i(TAG, "$modelName $id ${results.length()} done")
            // Checkpoint after every prompt: a killed class keeps what it reached.
            write(resultsDir, modelName, doc, results, exhausted = false, options)
        }

        return write(resultsDir, modelName, doc, results, exhausted, options)
    }

    private fun write(
        resultsDir: File,
        modelName: String,
        doc: JSONObject,
        results: JSONArray,
        exhausted: Boolean,
        options: Options,
    ): File {
        val statuses = (0 until results.length()).groupingBy {
            results.getJSONObject(it).getString("status")
        }.eachCount()
        val report = JSONObject()
            .put("suite", "benchmark")
            .put("model", modelName)
            .put("engine", engineName)
            .put(
                "params",
                "greedy topK=1 seed=7 thinking=false repeatPenalty=${options.repeatPenalty ?: "default"}",
            )
            .put("prompts_seed", doc.optInt("seed"))
            .put("completed", results.length())
            .put("ok", statuses["ok"] ?: 0)
            .put("errors", statuses["error"] ?: 0)
            .put("skipped", statuses["skipped"] ?: 0)
            .put("budget_exhausted", exhausted)
            .put("cases", results)

        resultsDir.mkdirs()
        // One file per run of a set selection, so per-set runs on a slow phone do not
        // overwrite each other; bench/report.py merges them by model and device.
        val sets = if (options.sets.isEmpty()) "" else "-" + options.sets.sorted().joinToString("+")
        val suffix = sets + (if (options.skip > 0) "@${options.skip}" else "") +
            (if (options.repeatPenalty != null) "-rp${options.repeatPenalty}" else "")
        val out = File(resultsDir, "$modelName.bench$suffix.json")
        val tmp = File(resultsDir, "$modelName.bench$suffix.json.tmp")
        tmp.writeText(report.toString(2))
        tmp.renameTo(out)
        return out
    }

    private fun tools(p: JSONObject): List<ToolDefinition> {
        val arr = p.optJSONArray("tools") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            ToolDefinition(
                name = t.getString("name"),
                description = t.getString("description"),
                parametersJson = t.getJSONObject("parameters").toString(),
            )
        }
    }

    private fun record(id: String, set: String, turn: ParitySuite.Turn): JSONObject = JSONObject()
        .put("id", id)
        .put("set", set)
        .put("status", "ok")
        .put("content", turn.content)
        .put("reasoning_chars", turn.reasoning.length)
        .put("raw", turn.raw)
        .put(
            "calls",
            JSONArray().apply {
                turn.calls.forEach {
                    put(JSONObject().put("name", it.name).put("arguments", it.argumentsJson))
                }
            },
        )
        .put("prompt_tokens", turn.done.stats.promptTokens)
        .put("generated_tokens", turn.done.stats.generatedTokens)
        .put("prefill_ms", turn.done.stats.prefillMs)
        .put("decode_ms", turn.done.stats.decodeMs)

    private suspend fun turn(
        engine: InferenceEngine,
        prompt: String,
        maxTokens: Int,
        tools: List<ToolDefinition>,
        options: Options,
    ): ParitySuite.Turn {
        val defaults = SamplerParams()
        val params = SamplerParams(
            // Thinking off on both engines. llama.cpp can cap a thinking block at a budget
            // and ExecuTorch cannot, so with thinking on the two would not be answering
            // under the same rule; the parity suite is where thinking families think.
            thinking = false,
            temperature = 0f,
            topK = 1,
            seed = 7,
            maxTokens = maxTokens,
            repeatPenalty = options.repeatPenalty ?: defaults.repeatPenalty,
        )
        val messages = listOf(ChatMessage.text(ChatRole.USER, prompt))
        val events = engine.chat(messages, params, tools).toList()
        val done = events.filterIsInstance<GenerationEvent.Completed>().single()
        val raw = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
        return ParitySuite.Turn(done, raw)
    }

    fun optionsFrom(args: Bundle): Options = Options(
        model = args.getString("model") ?: "",
        sets = (args.getString("sets") ?: "").split(',')
            .map(String::trim).filter(String::isNotEmpty).toSet(),
        limit = args.getString("limit")?.toIntOrNull() ?: 0,
        skip = args.getString("skip")?.toIntOrNull() ?: 0,
        budgetMinutes = args.getString("budget")?.toIntOrNull() ?: DEFAULT_BUDGET_MINUTES,
        repeatPenalty = args.getString("repeat")?.toFloatOrNull(),
    )

    private const val DEFAULT_BUDGET_MINUTES = 38
    private const val MS_PER_MINUTE = 60_000L
    private const val TAG = "BenchmarkSuite"
}

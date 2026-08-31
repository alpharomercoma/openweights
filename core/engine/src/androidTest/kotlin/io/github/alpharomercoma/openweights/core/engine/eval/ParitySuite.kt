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
 * The backend-parity suite: the same non-trivial conversations, against any engine.
 *
 * One suite so the comparison is fair by construction — a GGUF and a `.pte` of the same
 * family answer literally the same prompts under the same greedy sampling, and the
 * results land as JSON for `tools/eval/compare.py` to diff. The cases are chosen to
 * exercise capabilities, not trivia: a trap question that punishes pattern-matching, a
 * format constraint, a tool call and its result, cross-turn memory, and extraction from
 * a paragraph. Multi-turn cases feed back the model's *own* raw reply, the way the app
 * stores history, so the KV-cache path is the one really being tested.
 *
 * Scoring is programmatic and deliberately coarse: a regex the correct answer must
 * match. The JSON keeps every transcript, so a human (or a bigger model) can audit the
 * grades; the grades exist so reruns after a backend change diff mechanically.
 */
object ParitySuite {

    val WEATHER_TOOL = ToolDefinition(
        name = "get_weather",
        description = "Get the current weather for a city.",
        parametersJson = """{"type": "object", "properties": {"city": """ +
            """{"type": "string", "description": "City name"}}, "required": ["city"]}""",
    )

    /** Families whose format has no tool syntax; the tool cases are recorded as skipped. */
    private val TOOLLESS = listOf("smollm2", "gemma")

    private val PARAMS = SamplerParams(
        // Greedy everywhere: temperature zero and a single candidate. Reproducibility is
        // the point of a regression suite, and quality-at-temperature is a different
        // question from capability parity.
        temperature = 0f,
        topK = 1,
        seed = 7,
        maxTokens = 768,
    )

    private const val COUNCIL = "The Barangay San Isidro council met on Tuesday. After " +
        "reviewing the typhoon damage to the covered court, the members voted 6 to 1 to " +
        "move the annual fiesta from January 21 to March 14, and asked the treasurer to " +
        "release 45,000 pesos for repairs before the new date."

    suspend fun run(engine: InferenceEngine, modelName: String, resultsDir: File): File {
        val toolless = TOOLLESS.any { it in modelName.lowercase().filter(Char::isLetterOrDigit) }
        val results = JSONArray()

        // Single turns first.
        result(results, "trap-arithmetic", RESULT_NUMBER_9) {
            turn(
                engine,
                user(
                    "A farmer has 17 sheep. All but 9 run away. How many sheep does the " +
                        "farmer have left? Answer with just the number.",
                ),
            )
        }
        result(results, "multi-step-change", Regex("\\b16\\b")) {
            turn(
                engine,
                user(
                    "Ana buys 7 pencils at 12 pesos each and pays with a 100-peso bill. " +
                        "How many pesos of change does she receive? Answer with just the " +
                        "number.",
                ),
            )
        }
        result(results, "format-constraint", Regex("mercury[\"',\\s\\]]+venus[\"',\\s\\]]+earth", RegexOption.IGNORE_CASE)) {
            turn(
                engine,
                user(
                    "Return a JSON array containing exactly the lowercase names of the " +
                        "first three planets from the Sun, and nothing else.",
                ),
            )
        }
        result(results, "extraction", Regex("march\\s*14", RegexOption.IGNORE_CASE)) {
            turn(
                engine,
                user(
                    "$COUNCIL\n\nOn what date will the fiesta be held? Answer with just " +
                        "the date.",
                ),
            )
        }

        // Cross-turn memory, extending the model's own stored reply.
        result(results, "memory-across-turns", Regex("bagwis", RegexOption.IGNORE_CASE)) {
            val first = turn(engine, user("My cat is named Bagwis. Remember that."))
            turn(
                engine,
                user("My cat is named Bagwis. Remember that."),
                assistantRaw(first),
                user("What is my cat's name? Answer with just the name."),
            )
        }

        // The agentic pair: emit a call, then read its result.
        if (toolless) {
            results.put(skipped("tool-call")).put(skipped("tool-result"))
        } else {
            val ask = user("What is the current weather in Manila? Use the tool.")
            val call = turn(engine, ask, tools = listOf(WEATHER_TOOL))
            results.put(
                graded("tool-call", call) {
                    call.calls.any { it.name == "get_weather" && "manila" in it.argumentsJson.lowercase() }
                },
            )

            val followup = turn(
                engine,
                ask,
                assistantRaw(call),
                ChatMessage.toolResult(
                    "get_weather",
                    """{"temperature_c": 31, "condition": "humid"}""",
                ),
                tools = listOf(WEATHER_TOOL),
            )
            results.put(graded("tool-result", followup) { Regex("31").containsMatchIn(followup.content) })
        }

        val report = JSONObject()
            .put("model", modelName)
            .put("engine", engine::class.simpleName)
            .put("params", "greedy topK=1 seed=7 maxTokens=768")
            .put("cases", results)

        resultsDir.mkdirs()
        val out = File(resultsDir, "$modelName.json")
        out.writeText(report.toString(2))
        // Logcat carries the whole report too, so a run is auditable even if the pull of
        // the file fails.
        report.toString().chunked(3500).forEach { Log.i(TAG, it) }
        return out
    }

    private suspend fun result(
        into: JSONArray,
        id: String,
        expect: Regex,
        block: suspend () -> Turn,
    ) {
        val turn = runCatching { block() }
        into.put(
            turn.fold(
                onSuccess = { graded(id, it) { expect.containsMatchIn(it.content) } },
                onFailure = {
                    JSONObject().put("id", id).put("status", "error")
                        .put("error", it.toString())
                },
            ),
        )
    }

    private fun graded(id: String, turn: Turn, pass: () -> Boolean): JSONObject =
        JSONObject()
            .put("id", id)
            .put("status", if (pass()) "pass" else "fail")
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
            .put("cached_tokens", turn.done.stats.cachedTokens)
            .put("generated_tokens", turn.done.stats.generatedTokens)
            .put("prefill_ms", turn.done.stats.prefillMs)
            .put("decode_ms", turn.done.stats.decodeMs)

    private fun skipped(id: String): JSONObject =
        JSONObject().put("id", id).put("status", "skipped")
            .put("reason", "family has no tool syntax")

    private suspend fun turn(
        engine: InferenceEngine,
        vararg messages: ChatMessage,
        tools: List<ToolDefinition> = emptyList(),
    ): Turn {
        val events = engine.chat(messages.toList(), PARAMS, tools).toList()
        val done = events.filterIsInstance<GenerationEvent.Completed>().single()
        val raw = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
        return Turn(done, raw)
    }

    private fun user(text: String) = ChatMessage.text(ChatRole.USER, text)

    /** The model's own streamed text as stored history — the app's convention. */
    private fun assistantRaw(turn: Turn) =
        ChatMessage.text(ChatRole.ASSISTANT, turn.raw.ifEmpty { turn.content })

    data class Turn(val done: GenerationEvent.Completed, val raw: String) {
        val content get() = done.content
        val reasoning get() = done.reasoning
        val calls get() = done.toolCalls
    }

    private val RESULT_NUMBER_9 = Regex("(^|[^0-9.])9([^0-9]|$)")

    private const val TAG = "ParitySuite"
}

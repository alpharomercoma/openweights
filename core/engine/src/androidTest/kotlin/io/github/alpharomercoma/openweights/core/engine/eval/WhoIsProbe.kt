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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.engine.ExecuTorchEngine
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.NativeExecuTorchBridge
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What LFM2.5 1.2B on ExecuTorch writes, raw, when asked who somebody is.
 *
 * The phone showed "Let me look up information about alpha romer coma using a web search"
 * three times as the whole answer, and, once that announcement was caught and the model
 * pushed to call, "does not appear in current sources" with no search made. The app logs
 * counts and never the reply, so this is the instrument: the app's own instructions and
 * tool, the question as typed, and the raw text of each pass to logcat under [TAG].
 *
 * Arms: with and without the "(This question names X...)" note the app appends, and after
 * the push the denial repair sends. Greedy, then at the app's default temperature, three
 * seeds. A row per pass: arm, seed, whether a call was parsed, and the text.
 */
@RunWith(AndroidJUnit4::class)
class WhoIsProbe {

    @Test
    fun rawRepliesToWhoIs(): Unit = runBlocking {
        val model = WebsiteBuild.EVAL_DIR.resolve(PTE)
        val dump = WebsiteBuild.EVAL_DIR.resolve("prompt_dump.json")
        assumeTrue(
            "no $PTE or prompt_dump.json in ${WebsiteBuild.EVAL_DIR}",
            model.isFile && dump.isFile,
        )
        val system = org.json.JSONObject(dump.readText()).getString("system")
        val question = InstrumentationRegistry.getArguments().getString("question") ?: QUESTION
        val subject = question.removePrefix("who is ").trim()
        val noted =
            "$question\n\n(This question names $subject. Look it up with web_search before " +
                "answering rather than recalling it, and answer from what the search returns.)"

        for (temperature in listOf(0f, 0.8f)) {
            val engine = ExecuTorchEngine(NativeExecuTorchBridge(), temperature = temperature)
            try {
                engine.load(model, ModelLoadParams(contextLength = 4096))
                for (seed in 1..3) {
                    val params =
                        SamplerParams(temperature = temperature, seed = seed, maxTokens = 400)
                    val arms = listOf("noted" to noted, "bare" to question)
                    for ((arm, text) in arms) {
                        val history = mutableListOf(
                            ChatMessage.text(ChatRole.SYSTEM, system),
                            ChatMessage.text(ChatRole.USER, text),
                        )
                        val first = pass(engine, history, params)
                        row(temperature, seed, arm, 1, first)
                        if (first.calls == 0) {
                            history += ChatMessage.text(ChatRole.ASSISTANT, first.text)
                            history += ChatMessage.text(
                                ChatRole.USER,
                                "You do have a working tool for exactly this: web_search. " +
                                    "Call it now, with no apology and no explanation.",
                            )
                            val second = pass(engine, history, params)
                            row(temperature, seed, arm, 2, second)
                        }
                        if (seed > 1 && temperature == 0f) break
                    }
                    if (temperature == 0f) break
                }
            } finally {
                engine.unload()
            }
        }
        Log.i(TAG, "END")
    }

    private class Reply(val text: String, val calls: Int)

    private fun row(temperature: Float, seed: Int, arm: String, pass: Int, reply: Reply) {
        Log.i(
            TAG,
            "ROW t=$temperature seed=$seed arm=$arm pass=$pass calls=${reply.calls} " +
                "text=${reply.text.oneLine()}",
        )
    }

    private suspend fun pass(
        engine: InferenceEngine,
        history: List<ChatMessage>,
        params: SamplerParams,
    ): Reply {
        val events = engine.chat(history, params, TOOLS).toList()
        val done = events.filterIsInstance<GenerationEvent.Completed>().single()
        val raw = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
        return Reply(raw.ifEmpty { done.content }, done.toolCalls.size)
    }

    private fun String.oneLine(): String = replace("\n", "\\n").take(400)

    private companion object {
        const val TAG = "WhoIsProbe"
        const val PTE = "LFM2.5-1.2B-Instruct-8da4w-32k.pte"
        const val QUESTION = "who is alpha romer coma"

        val TOOLS = listOf(
            ToolDefinition(
                name = "web_search",
                description =
                "Search the web for what you cannot already know: what changed, what " +
                    "is recent, or the present state of a named person, product or organisation. " +
                    "Returns text; for pictures or clips use show_pictures. Not for settled " +
                    "knowledge (definitions, translations, history, arithmetic) and never " +
                    "to double check what you know: answer those yourself.",
                parametersJson =
                """{"type": "object", "properties": {"query": {"type": "string", """ +
                    """"description": "What to look up, as you would type it into a search """ +
                    """box"}}, """ +
                    """"required": ["query"]}""",
            ),
        )
    }
}

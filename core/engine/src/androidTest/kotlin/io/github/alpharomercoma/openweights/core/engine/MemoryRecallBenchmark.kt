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
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether a small model asks for what it has been told about the user, and only then.
 *
 * ### The question
 *
 * Recall in this app is a tool. `ReadMemoryTool` replaced a block injected into every
 * prompt, on two arguments: the block cost tokens on conversations that were not about
 * their user, and it entered ahead of the user's own words, which is where a prompt
 * injection would want to be. Its own documentation concedes the price and names this
 * measurement: "whether a small model pulls it is measured in the benchmark, not assumed
 * here." Nothing had measured it.
 *
 * The cost of getting it wrong runs both ways and neither is cheap. A miss is an app that
 * was told something and then does not know it, which is the whole feature failing
 * silently. An overcall is a round trip: a decode, a tool result, and a re-prefill of a
 * prompt that grew, which on a phone is seconds spent to learn nothing.
 *
 * ### The arms
 *
 * `tool` is what ships: the facts are behind `read_memory` and the model must think of it.
 * `injected` is what it replaced and what ChatGPT, Claude and Hermes all do in some form:
 * the facts sit in the system message, small and byte-stable, and there is nothing to call.
 * The second arm cannot miss and cannot overcall by construction, so what it is really
 * being measured on is whether the facts derail the questions that are not about them.
 *
 * ### Reading the output
 *
 * Per arm and per case: whether the fact reached the answer, and whether a call was made.
 * The numbers that decide it are `miss` on the depends-on-memory cases and `overcall` on
 * the rest. There is no assertion: the verdict is a judgement about which failure this app
 * would rather have, and it belongs in docs/research/memory-recall.md next to the table.
 *
 * ```
 * adb push LFM2.5-1.2B-Instruct-Q4_0.gguf /data/local/tmp/openweights/model.gguf
 * adb push Qwen3-1.7B-Q8_0.gguf /data/local/tmp/openweights/bench/qwen.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class MemoryRecallBenchmark {
    private var engine: InferenceEngine? = null

    @After
    fun tearDown() {
        engine?.let { runBlocking { it.close() } }
    }

    @Test
    fun everyModelIsMeasuredOnBothWaysOfRememberingSomebody(): Unit = runBlocking {
        val models = MODELS.filter { it.isFile }
        assumeTrue("no model under /data/local/tmp/openweights", models.isNotEmpty())

        Log.i(TAG, "model | arm | case | called | used the fact")
        for (model in models) {
            val fresh = LlamaCppEngine()
            engine?.close()
            engine = fresh
            fresh.load(model, ModelLoadParams(contextLength = CONTEXT))

            for (arm in Arm.entries) {
                var miss = 0
                var overcall = 0
                var calls = 0
                for (case in CASES) {
                    val completed = ask(fresh, arm, case.question)
                    val called = completed.toolCalls.any { it.name == READ_MEMORY }
                    val used = case.evidence.any { completed.content.contains(it, true) }
                    if (called) calls += 1
                    // A call on a question with nothing to do with the user is the round
                    // trip that costs seconds and learns nothing.
                    if (called && !case.dependsOnMemory) overcall += 1
                    // In the injected arm the facts were already there, so a miss is the
                    // model failing to use them; in the tool arm it may also be a model
                    // that never asked. Both are the same outcome for the user.
                    if (case.dependsOnMemory && !used) miss += 1
                    Log.i(
                        TAG,
                        "${model.name} | ${arm.name.lowercase()} | ${case.name} | " +
                            "$called | $used",
                    )
                }
                Log.i(
                    TAG,
                    "${model.name} | ${arm.name.lowercase()} | SUMMARY " +
                        "miss=$miss/${CASES.count { it.dependsOnMemory }} " +
                        "overcall=$overcall/${CASES.count { !it.dependsOnMemory }} " +
                        "calls=$calls",
                )
            }
        }
    }

    private suspend fun ask(
        engine: InferenceEngine,
        arm: Arm,
        question: String,
    ): GenerationEvent.Completed = engine.chat(
        messages = listOf(
            ChatMessage.text(ChatRole.SYSTEM, arm.systemMessage()),
            ChatMessage.text(ChatRole.USER, question),
        ),
        // The tool pass's own sampler, because deciding whether to call something is what
        // is being measured and that is the pass that decides.
        params = SamplerParams(temperature = 0f, maxTokens = BUDGET, seed = 7),
        tools = if (arm == Arm.TOOL) listOf(READ_MEMORY_TOOL) else emptyList(),
    ).toList().filterIsInstance<GenerationEvent.Completed>().single()

    /** How the facts reach the model, which is the whole of what is being compared. */
    private enum class Arm {
        /** What ships: behind a tool the model has to think of calling. */
        TOOL,

        /** What it replaced: in the system message, where there is nothing to think of. */
        INJECTED,
        ;

        fun systemMessage(): String = when (this) {
            TOOL -> INSTRUCTIONS
            INJECTED -> INSTRUCTIONS + "\n\n" + FACT_BLOCK
        }
    }

    /** One question, and how to tell from the answer whether the facts were used. */
    private class Case(
        val name: String,
        val question: String,
        val dependsOnMemory: Boolean,
        /** Words that can only be in the answer if a saved fact reached it. */
        val evidence: List<String>,
    )

    private companion object {
        const val TAG = "OpenWeightsMemoryRecall"
        const val CONTEXT = 4096
        const val BUDGET = 200
        const val READ_MEMORY = "read_memory"

        val MODELS = listOf(
            File("/data/local/tmp/openweights/model.gguf"),
            File("/data/local/tmp/openweights/bench/qwen.gguf"),
        )

        const val INSTRUCTIONS =
            "You are a helpful assistant running on the user's phone. Answer briefly."

        /**
         * The facts, in the shape `Memory.asPrompt` writes them.
         *
         * Three, which is what a real memory holds early on, and each one usable: an answer
         * that respects it is visibly different from one that does not.
         */
        val FACT_BLOCK = """
            Things you have been told about this user in earlier conversations. Use them if
            they are relevant and ignore them otherwise.
            1. The user is vegetarian and does not eat fish.
            2. The user lives in Manila.
            3. The user writes Kotlin and dislikes Python.
        """.trimIndent()

        /** The same definition `ReadMemoryTool` ships, so the arm measures what users get. */
        val READ_MEMORY_TOOL = ToolDefinition(
            name = READ_MEMORY,
            description = "Read the short facts saved about this user in earlier " +
                "conversations. Call it before answering anything that depends on who the " +
                "user is or what they prefer.",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {}
                }
            """.trimIndent(),
        )

        /**
         * Four that need the facts and four that do not.
         *
         * The four that do not are the overcall trap, and they are deliberately the kind of
         * question a model might think is personal: a general recipe, a general weather
         * question, arithmetic and a definition. A benchmark whose negatives are all
         * obviously impersonal cannot measure an overcall rate worth knowing.
         */
        val CASES = listOf(
            Case(
                name = "dinner",
                question = "Suggest one dinner for me tonight. Name the dish only.",
                dependsOnMemory = true,
                evidence = listOf("vegetarian", "vegetable", "tofu", "bean", "lentil", "paneer"),
            ),
            Case(
                name = "where",
                question = "What time zone should I use for my calendar?",
                dependsOnMemory = true,
                evidence = listOf("manila", "philippine", "pht", "utc+8", "gmt+8"),
            ),
            Case(
                name = "language",
                question = "Write me a one-line hello world. Nothing else.",
                dependsOnMemory = true,
                evidence = listOf("println", "fun main", "kotlin"),
            ),
            Case(
                name = "sushi",
                question = "Is the sushi place down the road a good idea for me?",
                dependsOnMemory = true,
                evidence = listOf("vegetarian", "fish", "do not eat", "don't eat"),
            ),
            Case(
                name = "arithmetic",
                question = "What is 12 times 12? Answer with digits only.",
                dependsOnMemory = false,
                evidence = emptyList(),
            ),
            Case(
                name = "definition",
                question = "What does the word 'perplexity' mean? One sentence.",
                dependsOnMemory = false,
                evidence = emptyList(),
            ),
            Case(
                name = "recipe",
                question = "How long should dried pasta boil for? One sentence.",
                dependsOnMemory = false,
                evidence = emptyList(),
            ),
            Case(
                name = "weather",
                question = "Explain in one sentence why it rains more in the tropics.",
                dependsOnMemory = false,
                evidence = emptyList(),
            ),
        )
    }
}

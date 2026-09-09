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
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the date should sit, decided on the phone rather than on a laptop.
 *
 * An instrument, not a test: it asserts nothing and a run of it is read. Its job is to
 * choose between shapes of the date exchange, and it exists because a host server said
 * one of them was clean and the device said it was not.
 *
 * The gap is the prompt around it. `eval/date_structure_eval.py` sends the sixteen-tool
 * catalogue from `prompt_dump.json`; a real turn sends whatever the user has switched on,
 * and the tools the model can see change what it does with a greeting as much as the
 * exchange does. That is not a bug in either measurement, it is the reason the device
 * gets the last word — and the reason the decision this probe makes is re-made here,
 * with a rerun, rather than argued from the host numbers.
 *
 * ```
 * adb shell am instrument -w -e class \
 *   io.github.alpharomercoma.openweights.ui.chat.DateStructureProbe \
 *   io.github.alpharomercoma.openweights.debug.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d -s OpenWeightsDate:*
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DateStructureProbe {

    /** One shape of the exchange: what goes between the head and the question. */
    private class Arm(val name: String, val before: () -> List<ChatMessage>)

    @Test
    fun compareTheShapes() = runBlocking<Unit> {
        val present = ToolChoiceBenchmark.MODELS.filter { it.value.isFile }
        assumeTrue("no models under ${ToolChoiceBenchmark.BENCH.path}", present.isNotEmpty())

        val day = PromptDay.pinned
        fun user(text: String) = ChatMessage.text(ChatRole.USER, text)
        fun assistant(text: String) = ChatMessage.text(ChatRole.ASSISTANT, text)
        val date = user("Today is $day.")
        val ready = listOf(user("Ready when you are."), assistant("Ready."))

        val arms = listOf(
            Arm("bare") { listOf(date, assistant("Understood, I have that.")) },
            Arm("scoped") { listOf(date, assistant(PromptDay.DATE_ACK)) },
            Arm("spaced") {
                listOf(date, assistant("Understood, I have that.")) + ready
            },
            Arm("spaced_scoped") { listOf(date, assistant(PromptDay.DATE_ACK)) + ready },
            Arm("nodate") { emptyList() },
        )

        for ((model, file) in present) {
            LlamaCppEngine().use { engine ->
                engine.load(file, ModelLoadParams(contextLength = ToolChoiceBenchmark.CONTEXT))
                for (arm in arms) {
                    var bled = 0
                    for (prompt in DateBleedOnDeviceTest.SMALLTALK) {
                        val said = answer(engine, arm.before() + user(prompt))
                        if (said.calls.isEmpty() && mentionsTheDate(said.text, day.year)) {
                            bled++
                            Log.i(
                                TAG,
                                "  BLEED $model/${arm.name}/$prompt: " +
                                    said.text.replace('\n', ' ').take(110),
                            )
                        }
                    }
                    val asked = answer(engine, arm.before() + user("What is today's date?"))
                    val answers = asked.calls.isEmpty() && "$day" in asked.text
                    Log.i(
                        TAG,
                        "RESULT $model ${arm.name}: bleed=$bled/" +
                            "${DateBleedOnDeviceTest.SMALLTALK.size} dateOK=$answers " +
                            "(${asked.calls}) ${asked.text.replace('\n', ' ').take(70)}",
                    )
                }
            }
        }
    }

    /**
     * The same question with every tool off, which is what a fresh install now sends.
     *
     * A different pass entirely: with no tool in the prompt there is no deciding pass, so
     * the reply is written by the answering sampler, at the user's temperature, without
     * the reasoning cap. Everything in [compareTheShapes] was measured greedy under the
     * tool catalogue, and none of it says what this pass does with the date.
     *
     * Three arms. The shipped exchange; the date as one line at the end of the
     * instructions, which lost with tools on because the tool block pushed it far from the
     * question, an argument that does not apply when there is no tool block; and no date,
     * the floor. The date question is asked of each, and with no tool to reach for, a
     * shape that loses it answers with a made-up day.
     */
    @Test
    fun compareTheShapesWithoutTools() = runBlocking<Unit> {
        val present = ToolChoiceBenchmark.MODELS.filter { it.value.isFile }
        assumeTrue("no models under ${ToolChoiceBenchmark.BENCH.path}", present.isNotEmpty())

        val day = PromptDay.pinned
        fun user(text: String) = ChatMessage.text(ChatRole.USER, text)
        val bareHead = ChatUiState(preferences = ModelPreferences()).prefixMessages()
        val datedHead = bareHead.map { message ->
            ChatMessage.text(message.role, message.text + "\n\nToday is $day.")
        }
        val arms = listOf(
            Triple("exchange", bareHead, PromptDay.exchange()),
            Triple("system_line", datedHead, emptyList()),
            Triple("nodate", bareHead, emptyList()),
        )

        for ((model, file) in present) {
            LlamaCppEngine().use { engine ->
                engine.load(file, ModelLoadParams(contextLength = ToolChoiceBenchmark.CONTEXT))
                for ((name, head, before) in arms) {
                    var bled = 0
                    for (prompt in DateBleedOnDeviceTest.SMALLTALK) {
                        val said = answerWithoutTools(engine, head + before + user(prompt))
                        if (mentionsTheDate(said, day.year)) {
                            bled++
                            Log.i(
                                TAG,
                                "  BLEED notools $model/$name/$prompt: " +
                                    said.replace('\n', ' ').take(110),
                            )
                        }
                    }
                    val asked = answerWithoutTools(
                        engine,
                        head + before + user("What is today's date?"),
                    )
                    Log.i(
                        TAG,
                        "RESULT notools $model $name: bleed=$bled/" +
                            "${DateBleedOnDeviceTest.SMALLTALK.size} dateOK=${"$day" in asked} " +
                            asked.replace('\n', ' ').take(70),
                    )
                }
            }
        }
    }

    /** One turn on the pass that writes the reply when no tool is on: the user's sampler. */
    private suspend fun answerWithoutTools(
        engine: LlamaCppEngine,
        messages: List<ChatMessage>,
    ): String {
        engine.resetContext()
        val completed = engine.chat(
            messages,
            ToolChoiceBenchmark.SHIPPED.copy(
                maxTokens = ToolChoiceBenchmark.BUDGET,
                seed = ToolChoiceBenchmark.SEED,
            ),
            tools = emptyList(),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()
        return parseAssistantReply(completed.content).answer
    }

    private class Said(val text: String, val calls: List<String>)

    /** One turn on the pass that writes the reply when tools are on: greedy, capped. */
    private suspend fun answer(engine: LlamaCppEngine, messages: List<ChatMessage>): Said {
        engine.resetContext()
        val completed = engine.chat(
            ToolChoiceBenchmark.head() + messages,
            ToolChoiceBenchmark.SHIPPED.copy(
                maxTokens = ToolChoiceBenchmark.BUDGET,
                seed = ToolChoiceBenchmark.SEED,
                temperature = 0f,
                repeatPenalty = 1f,
                reasoningBudget = TOOL_PASS_REASONING_BUDGET,
            ),
            tools = ToolChoiceBenchmark.probeCatalogue(),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()
        // What the reader sees, not what was decoded. A thinking model cut short by
        // the budget leaves an unclosed <think> in the raw content, and the UI shows
        // none of it; judging the raw string counted the model's private deliberation
        // about the date as a reply about the date.
        return Said(
            parseAssistantReply(completed.content).answer,
            completed.toolCalls.map { it.name },
        )
    }

    private fun mentionsTheDate(said: String, year: Int): Boolean {
        val low = said.lowercase()
        return "$year" in said ||
            "today is" in low ||
            "today's date" in low ||
            "today’s date" in low ||
            "current date" in low
    }

    private companion object {
        const val TAG = "OpenWeightsDate"
    }
}

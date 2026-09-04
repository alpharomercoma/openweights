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
import com.google.common.truth.Truth.assertWithMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Saying nothing in particular, and being answered about it rather than about the date.
 *
 * The reported bug: on a fresh chat, "hi" came back as a remark about today's date. The
 * cause is structural rather than cosmetic. The date rides on the conversation's first
 * user turn, so it is the nearest thing to a greeting, and a greeting carries nothing to
 * outweigh it:
 *
 *     hey → "It seems like you just mentioned today's date. Could you please tell me
 *            more about what you'd like to discuss?"
 *
 * This runs on the device and not on a server for one reason that decides the answer.
 * With tools on, which is the default, the pass that writes the reply is the pass that
 * may call a tool, so it runs greedily *and* under [TOOL_PASS_REASONING_BUDGET]. A
 * thinking model handed the whole budget deliberates about whether the date is relevant
 * and answers about the deliberation; handed 128 tokens it answers the person. No host
 * harness models that, so a host result about a thinking model is a different experiment.
 *
 * ```
 * adb push LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf /data/local/tmp/openweights/bench/lfm.gguf
 * adb push Qwen3-1.7B-Q8_0.gguf              /data/local/tmp/openweights/bench/qwen.gguf
 * adb shell am instrument -w -e class \
 *   io.github.alpharomercoma.openweights.ui.chat.DateBleedOnDeviceTest \
 *   io.github.alpharomercoma.openweights.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DateBleedOnDeviceTest {

    @Test
    fun smalltalkIsAnsweredRatherThanTheDate() = runBlocking<Unit> {
        val present = ToolChoiceBenchmark.MODELS.filter { it.value.isFile }
        assumeTrue("no models under ${ToolChoiceBenchmark.BENCH.path}", present.isNotEmpty())

        val bled = mutableListOf<String>()
        for ((name, file) in present) {
            LlamaCppEngine().use { engine ->
                engine.load(file, ModelLoadParams(contextLength = ToolChoiceBenchmark.CONTEXT))
                for (prompt in SMALLTALK) {
                    engine.resetContext()
                    // The app's own prefix and the app's own day exchange, byte for byte:
                    // a copy of them here would measure the copy.
                    val messages = ToolChoiceBenchmark.head() + PromptDay.exchange() +
                        ChatMessage.text(ChatRole.USER, prompt)
                    val completed = engine.chat(
                        messages,
                        // deciding(true): what TurnRunner uses on the pass that may call a
                        // tool, which with tools on is the pass whose prose the user reads.
                        ToolChoiceBenchmark.SHIPPED.copy(
                            maxTokens = ToolChoiceBenchmark.BUDGET,
                            seed = ToolChoiceBenchmark.SEED,
                            temperature = 0f,
                            repeatPenalty = 1f,
                            reasoningBudget = TOOL_PASS_REASONING_BUDGET,
                        ),
                        tools = catalogue(),
                    ).toList().filterIsInstance<GenerationEvent.Completed>().single()

                    // The answer as the screen renders it: a thinking model's private
                    // deliberation is not a reply, and an unclosed block shows as
                    // "thinking" rather than as text.
                    val said = parseAssistantReply(completed.content).answer
                    val calls = completed.toolCalls.map { it.name }
                    Log.i(TAG, "$name ${prompt.take(16)} calls=$calls")
                    Log.i(TAG, "  ${said.replace('\n', ' ').take(180)}")
                    if (calls.isEmpty() && mentionsTheDate(said)) {
                        bled += "$name/$prompt: ${said.replace('\n', ' ').take(120)}"
                    }
                }
            }
        }

        // Nothing, not "less than before". On the pass that writes the default reply the
        // old wording failed 3 of 16 greetings on LFM2.5-1.2B and the new one failed none,
        // measured by eval/date_structure_eval.py; this is the same claim on the phone.
        assertWithMessage("greetings answered about the date:\n${bled.joinToString("\n")}")
            .that(bled).isEmpty()
    }

    private fun catalogue(): List<ToolDefinition> = ToolChoiceBenchmark.probeCatalogue()

    private fun mentionsTheDate(said: String): Boolean {
        val low = said.lowercase()
        return PromptDay.pinned.toString() in said ||
            "${PromptDay.pinned.year}" in said ||
            "today is" in low ||
            "today's date" in low ||
            "current date" in low
    }

    internal companion object {
        const val TAG = "OpenWeights"

        /**
         * Things a person says that ask for nothing.
         *
         * The probe has to be empty of content: a greeting is the only prompt with nothing
         * in it to outweigh the turn before, which is why the bug shows here and nowhere
         * else. Sixteen of them, because six missed it.
         */
        internal val SMALLTALK = listOf(
            "hi", "hello", "hey", "yo", "good morning", "good evening",
            "thanks!", "thank you", "ok", "cool", "how are you?",
            "what's up?", "hi there", "hey!", "sup", "howdy",
        )
    }
}

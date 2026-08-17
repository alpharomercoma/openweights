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
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import io.github.alpharomercoma.openweights.core.engine.StopReason
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.Tool
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A whole tool turn against a real model: question, call, result, answer.
 *
 * Everything else about the loop is tested with the engine scripted, which is right for the
 * contracts and blind to the one thing scripting cannot check, which is whether a real
 * template can take a tool result back in and answer from it. Every part of that has worked
 * in isolation while the whole failed: definitions rendered but results dropped, results
 * carried but the assistant turn that asked for them missing, the second pass reading the
 * first one's tool syntax as text to continue.
 *
 * The proof is a word the model cannot know. [SECRET] appears nowhere in the question, in the
 * instructions, or in any plausible continuation of them; the only way into the answer is
 * through the tool. An answer that contains it is an answer that was written from a tool
 * result, which is the whole claim, and it is not a claim a model can fake its way into.
 *
 * Push a model first, as with the other device tests:
 * ```
 * adb push model.gguf /data/local/tmp/openweights/model.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ToolTurnOnDeviceTest {
    private lateinit var engine: InferenceEngine

    @Before
    fun setUp() {
        Fixtures.require("no test model at ${MODEL.path}", MODEL.isFile)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.close() }
    }

    @Test
    fun aToolResultReachesTheAnswer() = runBlocking<Unit> {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))
        Fixtures.require(
            "${MODEL.name} has a chat template that does not render tools",
            engine.loadedModel?.supportsTools == true,
        )

        val ledger = Ledger()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val runner =
            TurnRunner(
                engine,
                ToolRegistry(listOf(ledger)),
                ToolSwitches(context),
                PlanBoard(),
                AskBoard(),
            )
        val listener = Recording()

        val raw = runner.run(
            conversation = listOf(
                ChatMessage.text(
                    ChatRole.SYSTEM,
                    "You have tools. Use one when the answer is not something you know.",
                ),
                // Explicit, because what is being tested is the plumbing rather than the
                // judgement. Whether a model of this size decides to call is measured next
                // door, over cases where the decision is the question.
                ChatMessage.text(
                    ChatRole.USER,
                    "Look up the status of order 7788 with the check_order tool, " +
                        "then tell me what it says.",
                ),
            ),
            // Greedy, so a run that passes passes again. The app is greedy here too, for the
            // separate reason that choosing a tool is an argmax.
            params = SamplerParams(temperature = 0f, maxTokens = BUDGET, seed = 1),
            mode = AgentMode.AUTO,
            withTools = true,
            listener = listener,
        )

        val answer = parseAssistantReply(raw).answer.trim()
        Log.i(
            TAG,
            "calls=${ledger.calls} passes=${listener.passes.size} " +
                "answer=${answer.take(ANSWER_LOGGED)}",
        )

        // A model that will not route cannot prove anything here, and failing for that would
        // be failing for a judgement this test is not about. Loud in the log and skipped.
        Fixtures.require("${MODEL.name} did not call the tool at all", ledger.calls.isNotEmpty())

        // Called once, not once per round: with the same arguments each time, the second ask
        // is answered from the first run rather than run again.
        assertThat(ledger.calls).hasSize(1)
        assertThat(listener.steps.filterIsInstance<AgentStep.Ran>()).hasSize(1)
        // Two passes: the one that asked, and the one that answered. A third would mean the
        // model never got the result, or never recognised it.
        assertThat(listener.passes).hasSize(2)
        assertThat(listener.passes.last().reason)
            .isNoneOf(StopReason.ERROR, StopReason.CONTEXT_FULL)

        // The claim. This word exists only inside the tool.
        assertThat(answer).contains(SECRET)
        // And nothing a person would have to read past on the way to it.
        assertThat(answer).doesNotContain("<|tool_call_start|>")
        assertThat(answer).doesNotContain("<tool_call>")
    }

    /** A tool whose answer cannot be guessed, so its presence proves it was read. */
    private class Ledger : Tool {
        val calls = mutableListOf<ToolCall>()

        override val definition = ToolDefinition(
            name = "check_order",
            description = "Look up the delivery status of an order by its number.",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "order": { "type": "string", "description": "The order number" }
                  },
                  "required": ["order"]
                }
            """.trimIndent(),
        )

        override val needsApproval: Boolean = false

        override suspend fun run(call: ToolCall): String {
            calls += call
            return "Order 7788 is $SECRET."
        }
    }

    /** Keeps what the screen would have been shown. */
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
        const val TAG = "OpenWeightsTurn"
        const val CONTEXT = 4096

        /** Room to think and then answer, which a reasoning model needs twice over here. */
        const val BUDGET = 600

        /** Enough of the answer in the log to see what happened when this fails. */
        const val ANSWER_LOGGED = 300

        /**
         * Nonsense on purpose.
         *
         * A plausible status would be a word the model could reach on its own, and then a
         * turn that dropped the tool result entirely would still pass.
         */
        const val SECRET = "quartz-tangerine-41"

        val MODEL = File("/data/local/tmp/openweights/model.gguf")
    }
}

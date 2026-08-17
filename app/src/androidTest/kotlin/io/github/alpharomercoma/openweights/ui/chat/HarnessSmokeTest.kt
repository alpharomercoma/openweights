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
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import io.github.alpharomercoma.openweights.core.engine.StopReason
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole harness against a real model, asserting only what cannot be a matter of taste.
 *
 * The contracts of the loop are covered on the host, where the engine is scripted and every
 * pass is exactly what the test said it would be. This is the other question: whether the
 * instructions, the template and the parser still add up to a reply when a real model is on
 * the other end of them. It cannot ask whether the answer was any good, so it does not try.
 * Sampling is stochastic, models differ, and a suite that asserts on wording fails for
 * reasons nobody can act on, which is how a suite stops being run.
 *
 * What it does assert is that the turn ended, that nothing native went wrong, that the reply
 * is not empty, and that no tool syntax leaked into what a person would read. Each of those
 * has been broken here at least once.
 */
@RunWith(AndroidJUnit4::class)
class HarnessSmokeTest {
    private lateinit var engine: InferenceEngine

    @Before
    fun setUp() {
        assumeTrue("no test model at ${MODEL.path}", MODEL.isFile)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.close() }
    }

    @Test
    fun aQuestionTheModelKnowsIsAnsweredWithoutLeavingSyntaxBehind() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val runner = TurnRunner(
            engine,
            ToolRegistry(emptyList()),
            ToolSwitches(context),
            PlanBoard(),
            AskBoard(),
        )

        // Through the real instructions rather than a bare question, because the system
        // prompt is half of what is being smoke tested.
        val state = ChatUiState(
            transcript = listOf(
                TranscriptEntry(id = 1, role = ChatRole.USER, text = "What is 2 + 2?"),
            ),
            supportsTools = engine.loadedModel?.supportsTools == true,
        )

        val passes = mutableListOf<GenerationEvent.Completed>()
        val raw = runner.run(
            conversation = state.engineMessages(),
            params = SamplerParams(temperature = 0.1f, maxTokens = BUDGET, seed = 7),
            mode = AgentMode.AUTO,
            withTools = false,
            listener = Recording(passes),
        )

        val answer = parseAssistantReply(raw).answer.trim()
        Log.i(TAG, "reason=${passes.lastOrNull()?.reason} answer=${answer.take(200)}")

        // The turn ended on its own terms. ERROR is a native failure and CONTEXT_FULL means
        // the prompt did not fit, which for one short question would mean the instructions
        // had grown past the window.
        val reason = requireNotNull(passes.lastOrNull()).reason
        assertThat(reason).isNoneOf(StopReason.ERROR, StopReason.CONTEXT_FULL)
        assertThat(answer).isNotEmpty()
        // Nothing a person would have to read past. Both delimiter families, because which
        // one a model reaches for is a property of its template.
        assertThat(answer).doesNotContain("<|tool_call_start|>")
        assertThat(answer).doesNotContain("<tool_call>")
        assertThat(answer).doesNotContain("</think>")
    }

    /** Keeps the completions and ignores what the screen would have done with them. */
    private class Recording(private val passes: MutableList<GenerationEvent.Completed>) :
        TurnListener {
        override fun onText(raw: String) = Unit

        override fun onPass(event: GenerationEvent.Completed, raw: String) {
            passes += event
        }

        override fun onSteps(steps: List<AgentStep>) = Unit
        override fun onIntermediate(text: String) = Unit
        override fun onNextPass() = Unit
        override suspend fun onApproval(call: ToolCall): Boolean = false
    }

    private companion object {
        const val TAG = "OpenWeightsHarness"
        const val CONTEXT = 4096

        /** Room for a reasoning model to think and still answer. */
        const val BUDGET = 1200

        val MODEL = File("/data/local/tmp/openweights/model.gguf")
    }
}

/**
 * Consent already given, which is every turn after the first.
 *
 * The first one is [AgentRunnerTest]'s business: what it does is ask.
 */

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

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentRunner
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.Tool
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * The contracts the turn loop has to hold whatever the model does.
 *
 * These are about the harness rather than about any model's judgement, so the engine is
 * scripted: each pass says exactly what came back, and the assertions are on what the loop
 * did with it. Every one of them stands for a way the loop has actually gone wrong, which
 * is the only reason a contract is worth a test.
 */
@RunWith(RobolectricTestRunner::class)
class TurnRunnerTest {
    private val models: File = Files.createTempDirectory("openweights-turns").toFile()
    private lateinit var engine: FakeInferenceEngine
    private lateinit var search: RecordingTool

    @Before
    fun setUp() {
        engine = FakeInferenceEngine()
        search = RecordingTool("web_search")
    }

    @Test
    fun `a tool named in prose is not run when tools were never offered`() = runBlocking {
        // The model's template cannot render a tool, so the turn is asked to run without
        // them. What it writes is an ordinary answer that happens to name one.
        engine.scripted += ScriptedPass("I could use web_search for that, but here goes.")

        val steps = run(withTools = false)

        // Salvage exists for a model that decided to call a tool and got the syntax wrong.
        // A model that was never shown the tool has decided nothing, and running one on the
        // strength of a word in its answer is the app reaching the network on its own.
        assertThat(search.calls).isEmpty()
        assertThat(steps).isEmpty()
    }

    @Test
    fun `a tool named in prose is run when tools were on the table`() = runBlocking {
        engine.scripted += ScriptedPass("Let me web_search that.")
        engine.scripted += ScriptedPass("Here is the answer.")

        val steps = run(withTools = true)

        // The other half of the same rule: offered a tool and naming it is a decision, and
        // the only thing the model got wrong is the syntax.
        assertThat(search.calls).hasSize(1)
        assertThat(steps.filterIsInstance<AgentStep.Ran>()).hasSize(1)
    }

    @Test
    fun `the last pass is made to answer rather than asked for more tools`() = runBlocking {
        val call = ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"x"}""")
        // A model that would keep searching forever, to prove the loop stops it.
        repeat(PLENTY) { engine.scripted += ScriptedPass("Looking.", toolCalls = listOf(call)) }

        run(withTools = true)

        // Two rounds of tools and then one pass with none, so the model has to answer from
        // what it collected. Offering them again would leave the user with tool syntax and
        // no reply.
        assertThat(search.calls).hasSize(AgentRunner.DEFAULT_MAX_ROUNDS)
        assertThat(engine.offered).hasSize(AgentRunner.DEFAULT_MAX_ROUNDS + 1)
        assertThat(engine.offered.last()).isEmpty()
    }

    @Test
    fun `every call the model makes is answered before the next pass`() = runBlocking {
        val call = ToolCall(id = "7", name = "web_search", argumentsJson = """{"query":"x"}""")
        engine.scripted += ScriptedPass("Looking.", toolCalls = listOf(call))
        engine.scripted += ScriptedPass("Here is the answer.")

        run(withTools = true)

        // A model handed no result for a call it made cannot finish the turn, and the id
        // has to match or the result belongs to nothing.
        val second = engine.prompts[1]
        val results = second.filter { it.role == ChatRole.TOOL }
        assertThat(results).hasSize(1)
        assertThat(results.single().toolCallId).isEqualTo("7")
    }

    @Test
    fun `plan mode runs nothing and still reaches an answer`() = runBlocking {
        val call = ToolCall(id = "2", name = "web_search", argumentsJson = """{"query":"x"}""")
        // A model that ignores the instruction and calls anyway, which is what small ones do.
        engine.scripted += ScriptedPass("I will look this up.", toolCalls = listOf(call))
        engine.scripted += ScriptedPass("Here is what I would do.")

        val steps = run(withTools = true, mode = AgentMode.PLAN)

        assertThat(search.calls).isEmpty()
        assertThat(steps.filterIsInstance<AgentStep.Skipped>()).hasSize(1)
        // The turn is worth nothing if it ends on the fragment before the call. The model
        // is told the call did not run and given a pass in which to write the plan.
        assertThat(engine.prompts).hasSize(2)
    }

    /** Runs one turn and returns every step it reported. */
    private suspend fun run(
        withTools: Boolean,
        mode: AgentMode = AgentMode.AUTO,
    ): List<AgentStep> {
        engine.load(modelFile(), ModelLoadParams(contextLength = CONTEXT))
        val runner = TurnRunner(
            engine = engine,
            tools = ToolRegistry(listOf(search)),
            switches = ToolSwitches(ApplicationProvider.getApplicationContext()),
        )
        val steps = mutableListOf<AgentStep>()
        runner.run(
            conversation = listOf(ChatMessage.text(ChatRole.USER, "Who is Ada Lovelace?")),
            params = SamplerParams(),
            mode = mode,
            withTools = withTools,
            listener = Collecting(steps),
        )
        return steps
    }

    private fun modelFile(): File =
        File(models, "model.gguf").apply { writeText("not a real model") }

    /** A tool that records what it was asked rather than reaching anything. */
    private class RecordingTool(name: String) : Tool {
        val calls = mutableListOf<ToolCall>()

        override val definition = ToolDefinition(
            name = name,
            description = "Search the web.",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}""",
        )

        override suspend fun run(call: ToolCall): String {
            calls += call
            return "Ada Lovelace wrote the first algorithm."
        }

        override fun callFor(question: String): ToolCall? =
            ToolCall(id = "", name = definition.name, argumentsJson = """{"query":"$question"}""")
    }

    /** Keeps the steps and ignores everything the screen would have done with them. */
    private class Collecting(private val steps: MutableList<AgentStep>) : TurnListener {
        override fun onText(raw: String) = Unit
        override fun onPass(event: GenerationEvent.Completed, raw: String) = Unit
        override fun onSteps(steps: List<AgentStep>) {
            this.steps += steps
        }

        override fun onIntermediate(text: String) = Unit
        override fun onNextPass() = Unit
        override suspend fun onApproval(call: ToolCall): Boolean = true
    }

    private companion object {
        const val CONTEXT = 4096

        /** More passes than the loop is ever allowed to take. */
        const val PLENTY = 8
    }
}

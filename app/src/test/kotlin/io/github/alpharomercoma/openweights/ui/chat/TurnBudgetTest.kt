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
import io.github.alpharomercoma.openweights.core.tools.AdvanceTool
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentRunner
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.Tool
import io.github.alpharomercoma.openweights.core.tools.ToolNotes
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
 * What one question is allowed to spend, and what it is allowed to touch.
 *
 * Apart from [TurnRunnerTest] because that class is at detekt's size limit, and because
 * these are one subject rather than a miscellany: the budget a turn gets is earned by what
 * it does, and the plan a turn can tick belongs to the conversation that made it.
 */
@RunWith(RobolectricTestRunner::class)
class TurnBudgetTest {
    private val models: File = Files.createTempDirectory("openweights-budget").toFile()
    private lateinit var engine: FakeInferenceEngine
    private lateinit var search: RecordingTool

    @Before
    fun setUp() {
        engine = FakeInferenceEngine()
        engine.supportsTools = true
        search = RecordingTool("web_search")
    }

    @Test
    fun `the extra rounds are earned by chaining, not by having a chaining tool switched on`() =
        runBlocking {
            // A folder shared is not a reason to give every question in the app twice the
            // budget. This turn only ever searches, so it gets the ordinary two rounds even
            // though a chaining tool is sitting in the registry beside it.
            repeat(PLENTY) { round ->
                engine.scripted += ScriptedPass(
                    "Looking.",
                    toolCalls = listOf(
                        ToolCall("$round", "web_search", """{"query":"x$round"}"""),
                    ),
                )
            }

            run(beside = listOf(ChainingTool("read_file")))

            assertThat(search.calls).hasSize(AgentRunner.DEFAULT_MAX_ROUNDS)
        }

    @Test
    fun `a turn that actually chains gets the longer budget`() = runBlocking {
        // Find it, read it, write it is three rounds before a word reaches anybody, which is
        // what the longer budget is for. The first call earns it.
        val reader = ChainingTool("read_file")
        repeat(PLENTY) { round ->
            engine.scripted += ScriptedPass(
                "Reading.",
                toolCalls = listOf(ToolCall("$round", "read_file", """{"path":"n$round.md"}""")),
            )
        }

        run(beside = listOf(reader))

        assertThat(reader.calls).hasSize(AgentRunner.CHAINED_MAX_ROUNDS)
    }

    @Test
    fun `research that ends in a write is not refused the round to write in`() = runBlocking {
        // The regression the earned budget could have caused. Search, read the page, then
        // save what was found: the write is the third round, and by then the short budget
        // is spent. Asking for a chaining tool at that edge earns the longer budget rather
        // than being told there are no rounds left.
        val writer = ChainingTool("write_file")
        repeat(2) { round ->
            engine.scripted += ScriptedPass(
                "Looking.",
                toolCalls = listOf(ToolCall("s$round", "web_search", """{"query":"x$round"}""")),
            )
        }
        engine.scripted += ScriptedPass(
            "Saving.",
            toolCalls = listOf(ToolCall("w", "write_file", """{"path":"a.md","content":"x"}""")),
        )
        engine.scripted += ScriptedPass("Saved it.")

        run(beside = listOf(writer))

        assertThat(writer.calls).hasSize(1)
    }

    @Test
    fun `a run that does not own the plan is never offered advance`() = runBlocking {
        // A watch coming due mid-plan runs through this same loop, in AUTO, with nothing to
        // approve it, and the board holding the plan is one object for the whole process.
        // Being offered `advance` there means a scheduled check can tick a step of work the
        // user was doing in a conversation it has never seen.
        val plans = PlanBoard()
        plans.propose(
            "1. Read the notes\n2. Write the summary\n3. Check it",
        )
        engine.scripted += ScriptedPass("Nothing has changed since the last check.")

        runUnowned(plans)

        assertThat(engine.offered.last().map { it.name }).doesNotContain(AdvanceTool.NAME)
        // And the plan's own text is not pinned to the tail of the prompt either, which is
        // the position a small model attends to best: withholding only the tool would still
        // have a scheduled check answering about the steps of a chat it has never seen.
        assertThat(engine.prompts.last().joinToString { it.text }).doesNotContain("Read the notes")
    }

    private suspend fun run(beside: List<Tool> = emptyList()) {
        engine.load(modelFile(), ModelLoadParams(contextLength = CONTEXT))
        TurnRunner(
            engine = engine,
            tools = ToolRegistry(listOf(search) + beside),
            switches = ToolSwitches(ApplicationProvider.getApplicationContext()),
            plans = PlanBoard(),
            asks = AskBoard(),
        ).run(
            conversation = listOf(ChatMessage.text(ChatRole.USER, "Who is Ada Lovelace?")),
            params = SamplerParams(),
            mode = AgentMode.AUTO,
            withTools = true,
            notes = ToolNotes(),
            listener = Collecting,
        )
    }

    /** A turn that is not the conversation the plan belongs to, as a watch's tick is. */
    private suspend fun runUnowned(plans: PlanBoard) {
        engine.load(modelFile(), ModelLoadParams(contextLength = CONTEXT))
        val runner = TurnRunner(
            engine = engine,
            tools = ToolRegistry(listOf(search, AdvanceTool(plans))),
            switches = ToolSwitches(ApplicationProvider.getApplicationContext()),
            plans = plans,
            asks = AskBoard(),
        )
        runner.run(
            conversation = listOf(ChatMessage.text(ChatRole.USER, "Anything new?")),
            params = SamplerParams(),
            mode = AgentMode.AUTO,
            withTools = true,
            notes = ToolNotes(),
            listener = Collecting,
            offerPlan = false,
        )
    }

    /** A tool whose results are steps towards an answer rather than the answer. */
    private class ChainingTool(name: String) : Tool {
        val calls = mutableListOf<ToolCall>()

        override val definition = ToolDefinition(
            name = name,
            description = "Read a file.",
            parametersJson = """{"type":"object","properties":{"path":{"type":"string"}}}""",
        )

        override val chains: Boolean = true

        override suspend fun run(call: ToolCall): String {
            calls += call
            return "the contents of the file"
        }
    }

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
    }

    private object Collecting : TurnListener {
        override fun onText(raw: String) = Unit
        override fun onPass(event: GenerationEvent.Completed, raw: String) = Unit
        override fun onSteps(steps: List<AgentStep>) = Unit
        override fun onIntermediate(text: String) = Unit
        override fun onNextPass() = Unit
        override suspend fun onApproval(call: ToolCall): Boolean = true
    }

    private fun modelFile(): File =
        File(models, "model.gguf").apply { writeText("not a real model") }

    private companion object {
        const val CONTEXT = 4_096

        /** More passes than any budget allows, so the budget is what stops the turn. */
        const val PLENTY = 8
    }
}

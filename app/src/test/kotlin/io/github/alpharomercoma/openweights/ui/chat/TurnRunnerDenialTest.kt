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
 * The turn loop's answer to a model that says it cannot, with working tools in its prompt.
 *
 * What each of these stands for was measured, on-device and on a 34-case routing suite at
 * temperature zero: LFM2.5-1.2B opens a third of its replies with "I'm sorry, but I don't
 * have a tool that can...", for things its tools do and for things needing no tool at all.
 * The classification lives in [io.github.alpharomercoma.openweights.core.tools.CapabilityDenial]
 * and has its own test; these are about what the loop does with it.
 */
@RunWith(RobolectricTestRunner::class)
class TurnRunnerDenialTest {
    private val models: File = Files.createTempDirectory("openweights-denials").toFile()
    private lateinit var engine: FakeInferenceEngine
    private lateinit var search: RecordingTool

    @Before
    fun setUp() {
        engine = FakeInferenceEngine()
        engine.supportsTools = true
        search = RecordingTool("web_search")
    }

    @Test
    fun `a lookup denial buys a pass that keeps the tools`() = runBlocking<Unit> {
        // Verbatim from the routing suite: shown a working search tool, the model
        // denies having one. The pushed pass is the one the user used to type "go" for.
        engine.scripted += ScriptedPass(
            "I’m sorry, but I don’t have access to the latest information about that.",
        )
        engine.scripted += ScriptedPass(
            "Searching.",
            toolCalls = listOf(
                ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"q"}"""),
            ),
        )
        engine.scripted += ScriptedPass("Here is the answer.")

        run()

        val push = engine.prompts[1].last()
        assertThat(push.role).isEqualTo(ChatRole.USER)
        assertThat(push.text).contains("web_search")
        // The retry kept the tools on the table, and the push bought a real call.
        assertThat(engine.offered[1]).isNotEmpty()
        assertThat(search.calls).hasSize(1)
    }

    @Test
    fun `a denial about writing buys a pass with the tools withheld`() = runBlocking<Unit> {
        // A retry that can still see the tools calls one, measured: a haiku request
        // retried "with no apology" became a call to web_search. So this class of denial
        // is retried with nothing to call.
        engine.scripted += ScriptedPass(
            "I'm sorry, but I can't write or provide code for functions. My capabilities " +
                "are focused on searching the web.",
        )
        engine.scripted += ScriptedPass("function reverse(s) { return [...s].reverse().join('') }")

        run()

        assertThat(engine.offered).hasSize(2)
        // The retry keeps the definitions rendered: stripping them rewrote the front of
        // the prompt, which invalidated the KV cache at the tool block and cost a full
        // conversation re-read - twice, since the next turn put the block back. What is
        // withheld is the *parsing*: a call written on this pass is not run, which the
        // test below this one pins.
        assertThat(engine.offered[1]).isNotEmpty()
        assertThat(engine.prompts[1].last().text).contains("yourself")
        assertThat(search.calls).isEmpty()
    }

    @Test
    fun `a second denial does not buy a second pass`() = runBlocking {
        // Shared with the parse repair for the same reason that one is single-use:
        // re-asking a model that will not move is a minute spent arriving nowhere.
        engine.scripted += ScriptedPass(
            "I’m sorry, but I don’t have a tool that can directly identify that.",
        )
        engine.scripted += ScriptedPass(
            "I’m sorry, but I don’t have a tool that can directly identify that either.",
        )

        run()

        assertThat(engine.offered).hasSize(2)
    }

    @Test
    fun `a short denial naming a tool is a denial, not an announcement`() = runBlocking<Unit> {
        // Under the announcement salvage's length cap and naming a registered tool, so the
        // parse repair would claim it first and push the tools back at a model that just
        // said it cannot write. The denial classification has to win this race.
        engine.scripted += ScriptedPass(
            "I'm sorry, but I don't have a tool for writing code; I can only use web_search.",
        )
        engine.scripted += ScriptedPass("const answer = 42")

        run()

        // Rendered but not runnable: see the cache note two tests up.
        assertThat(engine.offered[1]).isNotEmpty()
        assertThat(engine.prompts[1].last().text).contains("yourself")
    }

    @Test
    fun `a call written on the prose retry is not run`() = runBlocking<Unit> {
        // The write-shaped retry is meant to be prose. For a model whose template drops
        // tools the catalogue cannot be un-rendered, so the only real withholding is
        // refusing to parse a call out of the retry — otherwise a haiku request that
        // apologised still ends in a web search, one pass later.
        engine.scripted += ScriptedPass(
            "I'm sorry, but I can't write or provide code for functions. My capabilities " +
                "are focused on searching the web.",
        )
        engine.scripted += ScriptedPass(
            "Searching anyway.",
            toolCalls = listOf(
                ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"q"}"""),
            ),
        )

        run()

        assertThat(search.calls).isEmpty()
        assertThat(engine.offered).hasSize(2)
    }

    @Test
    fun `a plan that reads denial-shaped is left standing`() = runBlocking {
        // A plan legitimately says what it cannot do yet, and the mode's whole promise is
        // a turn the user reads before anything happens. The push would replace the plan
        // with the direct answer the user asked to defer — caught by review, pinned here.
        engine.scripted += ScriptedPass(
            "I don't have access to the latest information, so I would search the web " +
                "for it and then summarise what I find.",
        )

        run(mode = AgentMode.PLAN)

        assertThat(engine.prompts).hasSize(1)
        assertThat(search.calls).isEmpty()
    }

    @Test
    fun `a knowledge lament buys the same pass a capability denial does`() = runBlocking<Unit> {
        // The Alpha Romer Coma shape: no capability noun, so denies() cannot see it,
        // and the whole answer is a shrug a working web_search disproves.
        engine.scripted += ScriptedPass(
            "I don't have enough information about Alpha Romer Coma to answer that.",
        )
        engine.scripted += ScriptedPass(
            "Searching.",
            toolCalls = listOf(
                ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"q"}"""),
            ),
        )
        engine.scripted += ScriptedPass("Here is who that is.")

        run()

        val push = engine.prompts[1].last()
        assertThat(push.role).isEqualTo(ChatRole.USER)
        assertThat(push.text).contains("web_search")
        assertThat(search.calls).hasSize(1)
    }

    @Test
    fun `sympathy is not a denial and ends the turn untouched`() = runBlocking {
        engine.scripted += ScriptedPass("I'm sorry for your loss. I'm here if you want to talk.")

        run()

        assertThat(engine.offered).hasSize(1)
        assertThat(search.calls).isEmpty()
    }

    private suspend fun run(mode: AgentMode = AgentMode.AUTO) {
        engine.load(modelFile(), ModelLoadParams(contextLength = 4096))
        val runner = TurnRunner(
            engine = engine,
            tools = ToolRegistry(listOf(search)),
            switches = ToolSwitches(ApplicationProvider.getApplicationContext()),
            plans = PlanBoard(),
            asks = AskBoard(),
        )
        runner.run(
            conversation = listOf(ChatMessage.text(ChatRole.USER, "Who is Ada Lovelace?")),
            params = SamplerParams(),
            mode = mode,
            withTools = true,
            notes = ToolNotes(),
            listener = Quiet,
        )
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

        override suspend fun run(call: ToolCall): String =
            "Ada Lovelace wrote the first algorithm.".also { calls += call }
    }

    private object Quiet : TurnListener {
        override fun onText(raw: String) = Unit
        override fun onPass(event: GenerationEvent.Completed, raw: String) = Unit
        override fun onSteps(steps: List<AgentStep>) = Unit
        override fun onIntermediate(text: String) = Unit
        override fun onNextPass() = Unit
        override suspend fun onApproval(call: ToolCall): Boolean = true
    }
}

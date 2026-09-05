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
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.AskUserTool
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.Tool
import io.github.alpharomercoma.openweights.core.tools.ToolNotes
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * The two 2026-09-05 additions to the loop: plan mode pushing once for the plan it asked
 * for, and a "who is" question carrying a note to look the name up.
 *
 * Apart from [TurnRunnerTest] because that class is at detekt's size limit. Every scripted
 * reply here is a shape one of the two test models actually produced on the host probe
 * behind `docs/research/plan-mode-and-recall.md`.
 */
@RunWith(RobolectricTestRunner::class)
class TurnRepairsTest {
    private val models: File = Files.createTempDirectory("openweights-repairs").toFile()
    private val engine = FakeInferenceEngine().apply { supportsTools = true }
    private val search = object : Tool {
        override val definition = ToolDefinition(
            name = "web_search",
            description = "Search the web.",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}""",
        )

        override suspend fun run(call: ToolCall): String = "Killua Zoldyck is from Hunter x Hunter."
    }

    @Test
    fun `plan mode pushes once when the model answered instead of planning`() = runBlocking {
        // What both test models do on "What is the capital of France?" in plan mode: answer.
        engine.scripted += ScriptedPass("The capital of France is Paris.")
        engine.scripted += ScriptedPass("1. Confirm the question is about France\n2. Answer it")

        planning()

        // Two passes, and the second was asked for the plan in so many words.
        assertThat(engine.prompts).hasSize(2)
        val push = engine.prompts[1].last { it.role == ChatRole.USER }
        assertThat(push.text).contains("not a plan")
        // The answer it gave is in the history the second pass reads, so the model can see
        // what it is being asked to replace rather than being asked cold.
        assertThat(engine.prompts[1].any { it.role == ChatRole.ASSISTANT && "Paris" in it.text })
            .isTrue()
    }

    @Test
    fun `a plan on the first pass is not pushed`() = runBlocking {
        engine.scripted += ScriptedPass("1. Find the notes\n2. Summarise them")

        planning()

        assertThat(engine.prompts).hasSize(1)
    }

    @Test
    fun `a clarifying question in plan mode is left standing`() = runBlocking {
        // The mode's other legitimate output: a model that asked in prose rather than
        // reaching for ask_user. Pushing it for a plan would answer the question for the user.
        engine.scripted += ScriptedPass("Which notes folder do you mean?")

        planning()

        assertThat(engine.prompts).hasSize(1)
    }

    @Test
    fun `plan mode pushes at most once`() = runBlocking<Unit> {
        engine.scripted += ScriptedPass("Paris.")
        engine.scripted += ScriptedPass("Still Paris.")
        engine.scripted += ScriptedPass("1. This\n2. Would be the plan")

        val raw = planning()

        // Two passes and the turn ends on the second answer; a model that answers twice
        // when asked for a plan is not asked a third time.
        assertThat(engine.prompts).hasSize(2)
        assertThat(raw).contains("Still Paris")
    }

    @Test
    fun `an ordinary answer outside plan mode is never pushed for a plan`() = runBlocking {
        engine.scripted += ScriptedPass("The capital of France is Paris.")

        answering("What is the capital of France?", withTools = true)

        assertThat(engine.prompts).hasSize(1)
    }

    @Test
    fun `a question naming somebody carries a note to look the name up`() = runBlocking {
        engine.scripted += ScriptedPass("Killua is a character in Naruto.")

        answering("Who is Killua?", withTools = true)

        val sent = engine.prompts.single().last { it.role == ChatRole.USER }.text
        assertThat(sent).startsWith("Who is Killua?")
        assertThat(sent).contains("names Killua")
        assertThat(sent).contains("web_search")
    }

    @Test
    fun `settled knowledge carries no such note`() = runBlocking {
        engine.scripted += ScriptedPass("4.")

        answering("What is 2+2?", withTools = true)

        val sent = engine.prompts.single().last { it.role == ChatRole.USER }.text
        assertThat(sent).isEqualTo("What is 2+2?")
    }

    @Test
    fun `the note is not attached when the search is not on offer`() = runBlocking {
        engine.scripted += ScriptedPass("Killua is a character in Naruto.")

        // Tools off: the trailer would tell the model to call something it cannot see.
        answering("Who is Killua?", withTools = false)

        val sent = engine.prompts.single().last { it.role == ChatRole.USER }.text
        assertThat(sent).isEqualTo("Who is Killua?")
    }

    @Test
    fun `plan mode strips the search, so the note goes with it`() = runBlocking {
        engine.scripted += ScriptedPass("1. Search for Killua\n2. Summarise the result")

        answering("Who is Killua?", withTools = true, mode = AgentMode.PLAN)

        val sent = engine.prompts.single().last { it.role == ChatRole.USER }.text
        assertThat(sent).isEqualTo("Who is Killua?")
    }

    /** One plan-mode turn on a request with nothing to act on, returning the last pass. */
    private suspend fun planning(): String = answering(
        "Summarise my notes",
        withTools = true,
        mode = AgentMode.PLAN,
    )

    private suspend fun answering(
        question: String,
        withTools: Boolean,
        mode: AgentMode = AgentMode.AUTO,
    ): String {
        engine.load(modelFile(), ModelLoadParams(contextLength = CONTEXT))
        val plans = PlanBoard()
        val asks = AskBoard()
        val runner = TurnRunner(
            engine = engine,
            tools = ToolRegistry(listOf(search, AdvanceTool(plans), AskUserTool(asks))),
            switches = ToolSwitches(ApplicationProvider.getApplicationContext()),
            plans = plans,
            asks = asks,
        )
        return runner.run(
            conversation = listOf(ChatMessage.text(ChatRole.USER, question)),
            params = SamplerParams(),
            mode = mode,
            withTools = withTools,
            notes = ToolNotes(),
            listener = Ignoring,
            question = question,
        )
    }

    private fun modelFile(): File =
        File(models, "model.gguf").apply { writeText("not a real model") }

    private object Ignoring : TurnListener {
        override fun onText(raw: String) = Unit
        override fun onPass(event: GenerationEvent.Completed, raw: String) = Unit
        override fun onSteps(steps: List<AgentStep>) = Unit
        override fun onIntermediate(text: String) = Unit
        override fun onNextPass() = Unit
        override suspend fun onApproval(call: ToolCall): Boolean = true
    }

    private companion object {
        const val CONTEXT = 4096
    }
}

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
 * Plan mode's machinery against the user's switches, which it does not follow.
 *
 * Apart from [TurnRunnerTest] because that class is at detekt's size limit.
 */
@RunWith(RobolectricTestRunner::class)
class PlanModeSwitchesTest {
    private val models: File = Files.createTempDirectory("openweights-plan").toFile()
    private val engine = FakeInferenceEngine().apply { supportsTools = true }
    private val search = object : Tool {
        override val definition = ToolDefinition(
            name = "web_search",
            description = "Search the web.",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}""",
        )

        override suspend fun run(call: ToolCall): String = "Ada Lovelace wrote the first algorithm."
    }

    @Test
    fun `plan mode keeps its own tools when every switch is off`() = runBlocking<Unit> {
        // The head counted only the tools the user has a switch for, and its answer became
        // the turn's "any tools at all": with every switch off, plan mode ran with nothing,
        // and lost ask_user and advance — the two tools the mode is made of — silently.
        val switches = ToolSwitches(ApplicationProvider.getApplicationContext())
        switches.setEnabled(search.definition.name, false)
        val plans = PlanBoard()
        val asks = AskBoard()
        plans.propose("1. Find the notes\n2. Summarise them")
        engine.load(modelFile(), ModelLoadParams(contextLength = CONTEXT))
        val runner = TurnRunner(
            engine = engine,
            tools = ToolRegistry(listOf(search, AdvanceTool(plans), AskUserTool(asks))),
            switches = switches,
            plans = plans,
            asks = asks,
        )
        engine.scripted += ScriptedPass("Here is the plan.")

        assertThat(runner.hasEnabledTools()).isFalse()
        assertThat(runner.hasEnabledTools(AgentMode.PLAN)).isTrue()
        runner.run(
            conversation = listOf(ChatMessage.text(ChatRole.USER, "Summarise my notes")),
            params = SamplerParams(),
            mode = AgentMode.PLAN,
            withTools = runner.hasEnabledTools(AgentMode.PLAN),
            notes = ToolNotes(),
            listener = Ignoring,
        )

        assertThat(engine.offered.single().map { it.name })
            .containsExactly(AskUserTool.NAME, AdvanceTool.NAME)
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

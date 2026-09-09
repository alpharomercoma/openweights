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
import io.github.alpharomercoma.openweights.core.engine.ContextWindowExceededException
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
 * The turn loop's answer to a window that will not take the prompt.
 *
 * A compiled model's window is fixed at export, and on a 2048 export the tool list is two
 * thirds of it: one question and its answer fill the rest, and the next question is refused
 * before compaction has anything to fold. Without the prefix the same conversation fits.
 */
@RunWith(RobolectricTestRunner::class)
class TurnRunnerOverflowTest {
    private val models: File = Files.createTempDirectory("openweights-overflow").toFile()
    private lateinit var engine: FakeInferenceEngine
    private lateinit var search: RecordingTool

    @Before
    fun setUp() {
        engine = FakeInferenceEngine()
        engine.supportsTools = true
        search = RecordingTool("web_search")
    }

    @Test
    fun `a window that refuses the tool prefix gets the same turn without it`() =
        runBlocking<Unit> {
            engine.overflowWhileToolsOffered = true
            engine.scripted += ScriptedPass("Alpha Romer Coma is a data engineer.")

            val answer = run()

            assertThat(answer).contains("data engineer")
            assertThat(engine.offered).hasSize(2)
            assertThat(engine.offered[0]).isNotEmpty()
            assertThat(engine.offered[1]).isEmpty()
            // The retry does not extend a cache that held the prefix.
            assertThat(engine.resetCount).isEqualTo(1)
            assertThat(search.calls).isEmpty()
        }

    @Test
    fun `a conversation that does not fit even without tools is refused as before`() =
        runBlocking<Unit> {
            engine.overflowAlways = true

            val failure = runCatching { run() }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ContextWindowExceededException::class.java)
            // Tried once with the prefix and once without; never a third time.
            assertThat(engine.offered).hasSize(2)
        }

    private suspend fun run(): String {
        engine.load(modelFile(), ModelLoadParams(contextLength = 2048))
        val runner = TurnRunner(
            engine = engine,
            tools = ToolRegistry(listOf(search)),
            switches = ToolSwitches(ApplicationProvider.getApplicationContext()),
            plans = PlanBoard(),
            asks = AskBoard(),
        )
        return runner.run(
            conversation = listOf(ChatMessage.text(ChatRole.USER, "who is alpha Romer coma")),
            params = SamplerParams(),
            mode = AgentMode.AUTO,
            withTools = true,
            notes = ToolNotes(),
            listener = Quiet,
        )
    }

    private fun modelFile(): File =
        File(models, "model.pte").apply { writeText("not a real model") }

    private class RecordingTool(name: String) : Tool {
        // A fake stands in for a tool the person switched on.
        override val defaultsOn: Boolean = true
        val calls = mutableListOf<ToolCall>()

        override val definition = ToolDefinition(
            name = name,
            description = "Search the web.",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}""",
        )

        override suspend fun run(call: ToolCall): String =
            "Alpha Romer Coma wrote this app.".also { calls += call }
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

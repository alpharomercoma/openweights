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
import io.github.alpharomercoma.openweights.core.tools.ToolExecution
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
        // A fake stands in for a tool the person switched on.
        override val defaultsOn: Boolean = true
        override val definition = ToolDefinition(
            name = "web_search",
            description = "Search the web.",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}""",
        )

        override suspend fun run(call: ToolCall): String = "Killua Zoldyck is from Hunter x Hunter."
    }

    @Test
    fun `a who-is question is searched by the app before the model writes a word`() =
        runBlocking<Unit> {
            // The phone, 2026-09-10: LFM2.5 1.2B on ExecuTorch made no call in sixteen
            // passes on "who is alpha romer coma", with the note, without it, and after the
            // push, and what it wrote instead was a biography of somebody else. So the app
            // searches first: one pass, with the results already in the prompt.
            engine.scripted += ScriptedPass("Killua Zoldyck is a character from Hunter x Hunter.")

            val reply = answering("who is killua zoldyck", withTools = true)

            assertThat(engine.prompts).hasSize(1)
            val prompt = engine.prompts.single()
            assertThat(prompt.last().role).isEqualTo(ChatRole.TOOL)
            assertThat(prompt.last().text).contains("Hunter x Hunter")
            // The asking turn is the app's one neutral line, not anything the model wrote.
            val asking = prompt.last { it.role == ChatRole.ASSISTANT }
            assertThat(asking.text).isEqualTo("Searching the web for killua zoldyck.")
            // And the note that asked the model to search is not on a question already searched.
            assertThat(
                prompt.first {
                    it.role == ChatRole.USER
                }.text,
            ).doesNotContain("names killua")
            assertThat(reply).contains("Hunter x Hunter")
        }

    @Test
    fun `the app searches once, and not for a question that names nobody`() = runBlocking<Unit> {
        engine.scripted += ScriptedPass("It is four.")

        val reply = answering("what is 2 + 2", withTools = true)

        assertThat(engine.prompts).hasSize(1)
        assertThat(engine.prompts.single().none { it.role == ChatRole.TOOL }).isTrue()
        assertThat(reply).isEqualTo("It is four.")
    }

    @Test
    fun `a model that would have called reads the results instead, one pass not two`() =
        runBlocking<Unit> {
            // Qwen3 called web_search itself on ten of ten named questions, at two passes a
            // turn and twenty to thirty seconds each on the phone. With the search made
            // first it answers in one.
            engine.scripted += ScriptedPass("Killua is from Hunter x Hunter.")

            answering("who is killua", withTools = true)

            assertThat(engine.prompts).hasSize(1)
            assertThat(engine.prompts.single().count { it.role == ChatRole.TOOL }).isEqualTo(1)
        }

    @Test
    fun `a search that fails puts the note back and the model answers as before`() =
        runBlocking<Unit> {
            // Offline, or rate limited: the tool ran and did not get there. Its failure
            // text is not evidence, so it is not handed to the model as results; the note
            // goes on the question instead, and the loop is the one that shipped before.
            val failing = object : Tool {
                override val defaultsOn: Boolean = true
                override val definition = search.definition

                override suspend fun run(call: ToolCall): String = execute(call).text

                override suspend fun execute(call: ToolCall): ToolExecution =
                    ToolExecution.failure("The device may be offline.")
            }
            engine.scripted += ScriptedPass("Killua is a character in Naruto.")

            answering("who is killua", withTools = true, tool = failing)

            assertThat(engine.prompts).hasSize(1)
            val prompt = engine.prompts.single()
            assertThat(prompt.none { it.role == ChatRole.TOOL }).isTrue()
            assertThat(prompt.last { it.role == ChatRole.USER }.text).contains("names killua")
        }

    @Test
    fun `a tool announced by its spoken name is still an announcement`() = runBlocking<Unit> {
        // Verbatim from the phone, 2026-09-10: LFM2.5 1.2B, one tool on, "who is alpha
        // romer coma". Three turns in a row this was the whole reply, shown as the answer,
        // because "web search" is not "web_search" and the salvage matched on the underscore.
        engine.scripted += ScriptedPass(
            "Let me look up information about alpha romer coma using a web search so I can " +
                "provide an accurate answer.",
        )
        engine.scripted += ScriptedPass(
            "Looking.",
            toolCalls = listOf(
                ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"alpha"}"""),
            ),
        )
        engine.scripted += ScriptedPass("Alpha Romer Coma is a developer.")

        val reply = answering("what changed in android 16", withTools = true)

        // A pass was spent handing the names back, the call it bought ran, and the answer
        // the user sees is the one written after it.
        val repair = engine.prompts[1].last()
        assertThat(repair.role).isEqualTo(ChatRole.USER)
        assertThat(repair.text).contains("web_search")
        assertThat(engine.prompts).hasSize(3)
        assertThat(reply).contains("developer")
    }

    @Test
    fun `a lookup announced with no tool named is an announcement where a lookup tool is on`() =
        runBlocking<Unit> {
            // The same decision with the name left out entirely. It counts only because
            // web_search is on offer; the test is first person and forward-looking, so
            // "I looked it up" and "you could look it up" are not this.
            engine.scripted += ScriptedPass("Let me look up who that is.")
            engine.scripted += ScriptedPass(
                "Looking.",
                toolCalls = listOf(
                    ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"x"}"""),
                ),
            )
            engine.scripted += ScriptedPass("Here is the answer.")

            answering("what changed in android 16", withTools = true)

            assertThat(engine.prompts[1].last().text).contains("web_search")
            assertThat(engine.prompts).hasSize(3)
        }

    @Test
    fun `short finished answers that mention searching are left standing`() = runBlocking<Unit> {
        // The false positives two reviewers found in the first draft, which counted any
        // short mention of "web search" as an announcement. Each is a complete answer:
        // a definition, a negative, a report of a search already made, a rhetorical
        // "let me check", and an offer with the verb six words from "let me".
        val answers = listOf(
            "Web search is a service for finding information on indexed websites.",
            "No web search is necessary; Alpha Romer Coma is the project's author.",
            "I found the answer through a web search: the author is Alpha Romer Coma.",
            "Let me check: Ottawa is the capital of Canada.",
            "Let me know if you want me to search for more.",
        )
        for (answer in answers) {
            engine.scripted.clear()
            engine.prompts.clear()
            engine.scripted += ScriptedPass(answer)

            val reply = answering("what is web search", withTools = true)

            assertThat(engine.prompts).hasSize(1)
            assertThat(reply).isEqualTo(answer)
        }
    }

    @Test
    fun `a second announcement after the repair is shown, not repaired again`() =
        runBlocking<Unit> {
            // One allowance a turn, like every repair: a model that announces twice is
            // going to announce a third time, and the phone should not pay to find out.
            engine.scripted += ScriptedPass("Let me look up who that is.")
            engine.scripted += ScriptedPass("I'll search for that now.")

            val reply = answering("what changed in android 16", withTools = true)

            assertThat(engine.prompts).hasSize(2)
            assertThat(reply).isEqualTo("I'll search for that now.")
        }

    @Test
    fun `a finished answer that says it looked something up is not an announcement`() =
        runBlocking<Unit> {
            engine.scripted += ScriptedPass(
                "I looked it up: Alpha Romer Coma is a developer who publishes open-source " +
                    "Android work. You could look up the repository for the full history.",
            )

            val reply = answering("what changed in android 16", withTools = true)

            assertThat(engine.prompts).hasSize(1)
            assertThat(reply).contains("developer")
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

        // With the app's own search off, which is the loop as it shipped until 2026-09-10.
        answering("Who is Killua?", withTools = true, searchesFirst = false)

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
        searchesFirst: Boolean = true,
        tool: Tool = search,
    ): String {
        engine.load(modelFile(), ModelLoadParams(contextLength = CONTEXT))
        val plans = PlanBoard()
        val asks = AskBoard()
        val runner = TurnRunner(
            engine = engine,
            tools = ToolRegistry(listOf(tool, AdvanceTool(plans), AskUserTool(asks))),
            switches = ToolSwitches(ApplicationProvider.getApplicationContext()),
            plans = plans,
            asks = asks,
        ).apply { searchesForSubject = searchesFirst }
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

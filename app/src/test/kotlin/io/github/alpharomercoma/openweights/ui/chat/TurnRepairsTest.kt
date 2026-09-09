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
    fun `a search the model announces and does not make is made by the app`() = runBlocking<Unit> {
        // The phone, 2026-09-10: the compiled LFM2.5 1.2B announced a search on "who
        // is alpha romer coma" three turns running and never wrote the call, before
        // and after the push that hands the tool names back. The decision was made
        // and said; the app carries it out, on the question as asked, and the model
        // answers next pass with the results in front of it.
        engine.scripted += ScriptedPass("Let me look up who that is using a web search.")
        engine.scripted += ScriptedPass("Killua Zoldyck is a character from Hunter x Hunter.")

        val reply = answering("who is killua zoldyck", withTools = true)

        assertThat(engine.prompts).hasSize(2)
        val prompt = engine.prompts[1]
        assertThat(prompt.last().role).isEqualTo(ChatRole.TOOL)
        assertThat(prompt.last().text).contains("Hunter x Hunter")
        // The asking turn is the app's one neutral line; the announcement is gone.
        val asking = prompt.last { it.role == ChatRole.ASSISTANT }
        assertThat(asking.text).isEqualTo("Searching the web for: who is killua zoldyck")
        assertThat(prompt.none { it.text.contains("Let me look up") }).isTrue()
        assertThat(reply).contains("Hunter x Hunter")
    }

    @Test
    fun `a search the model claims to have made is made real`() = runBlocking<Unit> {
        // One reply in five on 160 public rows, from the same model: "Based on my search,
        // the screenwriter was Miguel Aznar", with no search made. Shown as the answer,
        // that is a lie with a citation. The claim is the decision; the app makes it true
        // and the answer is written from what the search actually returned.
        engine.scripted += ScriptedPass(
            "Based on my search, Killua Zoldyck is a character in Naruto who fights Sasuke.",
        )
        engine.scripted += ScriptedPass("Killua Zoldyck is from Hunter x Hunter.")

        val reply = answering("who is killua zoldyck", withTools = true)

        assertThat(engine.prompts).hasSize(2)
        assertThat(engine.prompts[1].last().role).isEqualTo(ChatRole.TOOL)
        assertThat(engine.prompts[1].none { it.text.contains("Naruto") }).isTrue()
        assertThat(reply).contains("Hunter x Hunter")
    }

    @Test
    fun `a lament about not knowing somebody is searched, not pushed`() = runBlocking<Unit> {
        engine.scripted += ScriptedPass("I don't have enough information about Killua Zoldyck.")
        engine.scripted += ScriptedPass("Killua Zoldyck is from Hunter x Hunter.")

        val reply = answering("who is killua zoldyck", withTools = true)

        assertThat(engine.prompts).hasSize(2)
        assertThat(engine.prompts[1].last().role).isEqualTo(ChatRole.TOOL)
        assertThat(reply).contains("Hunter x Hunter")
    }

    @Test
    fun `the app searches once a turn, and a model that announces twice is shown its second`() =
        runBlocking<Unit> {
            // One allowance, like every repair: a model handed real results that announces
            // again is going to announce a third time, and the phone should not pay to
            // find out.
            engine.scripted += ScriptedPass("Let me look up who that is.")
            engine.scripted += ScriptedPass("I'll search for that now.")

            val reply = answering("who is killua zoldyck", withTools = true)

            assertThat(engine.prompts).hasSize(2)
            assertThat(reply).isEqualTo("I'll search for that now.")
        }

    @Test
    fun `the app searches nothing for an answer that answers`() = runBlocking<Unit> {
        engine.scripted += ScriptedPass("It is four.")

        val reply = answering("what is 2 + 2", withTools = true)

        assertThat(engine.prompts).hasSize(1)
        assertThat(engine.prompts.single().none { it.role == ChatRole.TOOL }).isTrue()
        assertThat(reply).isEqualTo("It is four.")
    }

    @Test
    fun `a search that fails falls through to the push, and the model answers as before`() =
        runBlocking<Unit> {
            // Offline, or rate limited: the tool ran and did not get there. Its failure
            // text is not evidence, so it is not handed to the model as results; the
            // announcement takes the push it always took.
            val failing = object : Tool {
                override val defaultsOn: Boolean = true
                override val definition = search.definition

                override suspend fun run(call: ToolCall): String = execute(call).text

                override suspend fun execute(call: ToolCall): ToolExecution =
                    ToolExecution.failure("The device may be offline.")
            }
            engine.scripted += ScriptedPass("Let me look up who that is.")
            engine.scripted += ScriptedPass("Killua is a character in Naruto.")

            answering("who is killua", withTools = true, tool = failing)

            assertThat(engine.prompts).hasSize(2)
            assertThat(engine.prompts[1].none { it.role == ChatRole.TOOL }).isTrue()
            assertThat(engine.prompts[1].last().text).contains("web_search")
        }

    @Test
    fun `a claim after a real search is the model reading its results, not a second search`() =
        runBlocking<Unit> {
            engine.scripted += ScriptedPass(
                "Looking.",
                toolCalls = listOf(
                    ToolCall(
                        id = "1",
                        name = "web_search",
                        argumentsJson = """{"query":"killua"}""",
                    ),
                ),
            )
            engine.scripted +=
                ScriptedPass("Based on the search results, Killua is from Hunter x Hunter.")

            val reply = answering("who is killua zoldyck", withTools = true)

            assertThat(engine.prompts).hasSize(2)
            assertThat(reply).contains("Based on the search results")
        }

    @Test
    fun `answers that talk about searching without claiming one are left standing`() =
        runBlocking<Unit> {
            // The false positives two reviewers found against a looser matcher: an answer
            // about search engines, a summary of somebody else's search, a question to the
            // user, a withdrawn announcement, and a question about research.
            val cases = listOf(
                "how does elasticsearch rank pages" to
                    "Elasticsearch scores documents with BM25. The search results are ordered by score.",
                "summarise what the police found in this report: " + "x".repeat(500) to
                    "The search reveals no evidence of foul play, according to the report.",
                "who is killua" to "Should I look that up for you?",
                "who is killua" to
                    "Don't let me look that up; you already know he is from Hunter x Hunter.",
                "what did this research conclude" to
                    "Based on the search strategy described, the review excluded preprints.",
                // Mistral's three: an offer, and two searches of the model's own head.
                "who is killua" to
                    "I can look it up if you want; I believe he is from Hunter x Hunter.",
                "who is killua" to
                    "Let me search my memory. Killua Zoldyck is from Hunter x Hunter.",
                "who is killua" to
                    "I looked it up in my notes: Killua Zoldyck is from Hunter x Hunter.",
            )
            for ((question, answer) in cases) {
                engine.scripted.clear()
                engine.prompts.clear()
                engine.scripted += ScriptedPass(answer)

                val reply = answering(question, withTools = true)

                assertThat(engine.prompts).hasSize(1)
                assertThat(reply).isEqualTo(answer)
            }
        }

    @Test
    fun `nothing of the user's own is searched on the app's initiative`() = runBlocking<Unit> {
        engine.scripted += ScriptedPass("I don't have that information.")

        val reply = answering("what is my wifi password", withTools = true)

        assertThat(engine.prompts.flatten().none { it.role == ChatRole.TOOL }).isTrue()
        assertThat(reply).isNotEmpty()
    }

    @Test
    fun `the query is the question with its wrapping taken off`() {
        val none = emptyList<ChatMessage>()
        assertThat(
            searchQuery("Can you quickly check who directed The Last Word for me please?", none),
        )
            .isEqualTo("who directed The Last Word")
        assertThat(searchQuery("who is killua zoldyck?", none)).isEqualTo("who is killua zoldyck")
        // A pronoun-only follow-up carries the previous question's subject.
        val earlier = listOf(
            ChatMessage.text(ChatRole.USER, "Who founded Anthropic?"),
            ChatMessage.text(ChatRole.ASSISTANT, "Dario Amodei and others."),
            ChatMessage.text(ChatRole.USER, "Where did he work before?"),
        )
        assertThat(searchQuery("Where did he work before?", earlier))
            .isEqualTo("Who founded Anthropic Where did he work before")
        // A long message keeps its last question and nothing sent is longer than a search box takes.
        val long = "I'm preparing a quiz for my class tomorrow and I want to be sure about the " +
            "details of a few films before I print the sheets, so could you please tell me " +
            "who directed The Last Word?"
        assertThat(searchQuery(long, none)).isEqualTo("who directed The Last Word")
    }

    @Test
    fun `with the switch off, the announcement takes the push it used to`() = runBlocking<Unit> {
        // The baseline arm of the on-device decision suite, so what the app's search buys
        // can be priced against the same model on the same phone.
        engine.scripted += ScriptedPass("Let me look up who that is.")
        engine.scripted += ScriptedPass(
            "Looking.",
            toolCalls = listOf(
                ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"x"}"""),
            ),
        )
        engine.scripted += ScriptedPass("Here is the answer.")

        answering("who is killua", withTools = true, honours = false)

        assertThat(engine.prompts[1].last().text).contains("web_search")
        assertThat(engine.prompts).hasSize(3)
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
        engine.scripted += ScriptedPass("Alpha Romer Coma is a developer.")

        val reply = answering("what changed in android 16", withTools = true)

        // Recognised as the decision it is, and carried out.
        assertThat(engine.prompts).hasSize(2)
        assertThat(engine.prompts[1].last().role).isEqualTo(ChatRole.TOOL)
        assertThat(reply).contains("developer")
    }

    @Test
    fun `a lookup announced with no tool named is an announcement where a lookup tool is on`() =
        runBlocking<Unit> {
            // The same decision with the name left out entirely. It counts only because
            // web_search is on offer; the test is first person and forward-looking, so
            // "I looked it up" and "you could look it up" are not this.
            engine.scripted += ScriptedPass("Let me look up who that is.")
            engine.scripted += ScriptedPass("Here is the answer.")

            answering("what changed in android 16", withTools = true)

            assertThat(engine.prompts[1].last().role).isEqualTo(ChatRole.TOOL)
            assertThat(engine.prompts).hasSize(2)
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
    fun `a finished answer that says it looked something up is a claim, and is made true`() =
        runBlocking<Unit> {
            // Not an announcement: past tense, nothing to hand back. But no search ran,
            // so "I looked it up" reports a search that never happened, and the app
            // makes it one rather than show the claim.
            engine.scripted += ScriptedPass(
                "I looked it up: Alpha Romer Coma is a developer who publishes open-source " +
                    "Android work. You could look up the repository for the full history.",
            )
            engine.scripted += ScriptedPass("Alpha Romer Coma is a developer of OpenWeights.")

            val reply = answering("what changed in android 16", withTools = true)

            assertThat(engine.prompts).hasSize(2)
            assertThat(reply).contains("OpenWeights")
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
        honours: Boolean = true,
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
        ).apply { honoursIntent = honours }
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

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

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.context.GoalState
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.UserQuestion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The part of this app that runs without anybody watching.
 *
 * A goal plans its own steps, takes them one at a time, holds a foreground service across
 * the gaps, and decides for itself when to stop. It is the longest-running and least
 * supervised thing here, and until this file it had no tests at all: every other surface
 * fails in front of somebody, and this one fails on a phone in a pocket.
 *
 * What is asserted here is the stopping, not the succeeding. A goal that answers well is a
 * property of the model; a goal that halts when there is no plan, gives up after failing
 * repeatedly, refuses to start twice, and lets go of the screen when stopped is a property
 * of this code, and each of those is the difference between an idle app and a flat battery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoalLoopTest : ChatFixture() {
    @Test
    fun `a goal with no plan behind it halts and says so`() = runTest(dispatcher) {
        loadModel()

        viewModel.startGoal("Tidy the notes folder")
        settle()
        // The planning turn answers with prose rather than a plan, which is the ordinary
        // failure of a small model asked to plan. Nothing proposes anything to the board.
        engine.finish("I would probably start by looking at the folder.")
        settle()

        val goal = goals.goal.value
        assertThat(goal?.state).isEqualTo(GoalState.HALTED)
        assertThat(goal?.note).contains("No plan came back")
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
    }

    @Test
    fun `a goal waits for its planning turn rather than deciding there is no plan`() =
        runTest(dispatcher) {
            loadModel()
            // Held, so the planning turn is still in flight when the loop looks at the
            // board. That is every real run: a phone takes seconds to produce a plan.
            engine.hold = true

            viewModel.startGoal("Tidy the notes folder")
            settle()

            // The prompt reached the engine and no answer has come back yet, so there is
            // nothing to conclude. Reading the empty board here and halting is the loop
            // overtaking its own turn.
            assertThat(engine.prompts).isNotEmpty()
            assertThat(goals.goal.value?.state).isEqualTo(GoalState.PLANNING)
        }

    @Test
    fun `a goal cannot be started on top of one already running`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true

        viewModel.startGoal("First task")
        settle()
        val running = goals.goal.value

        viewModel.startGoal("Second task")
        settle()

        // The board still holds the first, rather than the second having replaced it and
        // left two loops driving one engine.
        assertThat(goals.goal.value?.task).isEqualTo(running?.task)
        assertThat(goals.goal.value?.task).isEqualTo("First task")
    }

    /**
     * A second goal, started in the same conversation once the first is done, used to read
     * the first's plan back as its own: nothing ever cleared the board between them, so a
     * planning turn that answered in prose rather than a plan still found a non-null plan
     * sitting there and ran with it — the wrong task's steps, or, once every one of them was
     * already ticked, a goal stuck WORKING with nothing left for it to do.
     */
    @Test
    fun `a second goal does not inherit a stale plan from the one before it`() =
        runTest(dispatcher) {
            loadModel()
            engine.scripted += ScriptedPass("1. Do the first thing\n2. Do the second thing")
            viewModel.startGoal("First task")
            awaitGoalSettled()
            assertThat(goals.goal.value?.state).isEqualTo(GoalState.DONE)

            // No scripted reply this time: the default is not parseable as a plan.
            viewModel.startGoal("Second task")
            awaitGoalSettled()

            val goal = goals.goal.value
            assertThat(goal?.task).isEqualTo("Second task")
            assertThat(goal?.state).isEqualTo(GoalState.HALTED)
            assertThat(goal?.note).contains("No plan came back")
        }

    /**
     * Stopping only the turn in flight left a goal's own loop free to keep running: it reads
     * the board rather than the transcript to decide whether to take another step, so it
     * found itself still marked running and started the next one against whatever
     * conversation newChat had, by then, already switched to.
     */
    @Test
    fun `starting a new chat stops a goal that is still running`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true
        viewModel.startGoal("Something long")
        settle()
        assertThat(goals.goal.value?.isRunning).isTrue()

        viewModel.newChat()
        settle()

        assertThat(goals.goal.value).isNull()
    }

    @Test
    fun `a blank goal starts nothing`() = runTest(dispatcher) {
        loadModel()

        viewModel.startGoal("   ")
        settle()

        assertThat(goals.goal.value).isNull()
        assertThat(engine.prompts).isEmpty()
    }

    @Test
    fun `stopping a goal stops the goal and frees the screen`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true
        viewModel.startGoal("Something long")
        settle()
        assertThat(goals.goal.value?.isRunning).isTrue()

        viewModel.stopGoal()
        settle()

        assertThat(goals.goal.value?.state).isEqualTo(GoalState.STOPPED)
        assertThat(goals.goal.value?.isRunning).isFalse()
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
    }

    /**
     * The exact shape reported live: a goal stopped, but the question it had asked stayed on
     * screen with no way to satisfy it, because the coroutine that had been waiting for an
     * answer was never told the run it belonged to was already over.
     */
    @Test
    fun `stopping a goal releases a question it left pending`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true
        viewModel.startGoal("Something long")
        settle()

        var answer: String? = null
        launch { answer = turns.asking.ask(UserQuestion("Which one?")) }
        settle()
        assertThat(turns.asking.pending.value).isNotNull()

        viewModel.stopGoal()
        settle()

        assertThat(turns.asking.pending.value).isNull()
        assertThat(answer).isEqualTo("")
    }

    /**
     * The board is one object for the whole app, so a goal that finished in one conversation
     * used to still be there, naming its old task, the moment a different one was opened.
     */
    @Test
    fun `starting a new chat clears a goal and question left over from another one`() =
        runTest(dispatcher) {
            loadModel()
            engine.hold = true
            viewModel.startGoal("Something long")
            settle()
            viewModel.stopGoal()
            settle()
            assertThat(goals.goal.value?.isRunning).isFalse()

            var answer: String? = null
            launch { answer = turns.asking.ask(UserQuestion("Which one?")) }
            settle()

            viewModel.newChat()
            settle()

            assertThat(goals.goal.value).isNull()
            assertThat(turns.asking.pending.value).isNull()
            assertThat(answer).isEqualTo("")
        }

    @Test
    fun `what is typed while a goal runs is kept for the next step`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true
        viewModel.startGoal("Work through it")
        settle()

        viewModel.steerGoal("Prefer the shorter option")
        settle()

        // Held rather than delivered: reading it mid-turn would corrupt the turn in flight,
        // so it waits for the boundary. What matters is that it was not dropped.
        assertThat(goals.steering.value).contains("Prefer the shorter option")
    }

    /**
     * The exact shape reported live: asked to research "who is alpha romer coma", the model
     * asked the person who Alpha Romer was instead of putting it on the plan, because plan
     * mode's only other tool is ask_user and not recognising a name reads as ambiguity to a
     * small model. Not knowing the subject of a research request is not that; it is usually
     * the plan's first question, and the planning prompt has to say so rather than leaving a
     * small model to reach for the one tool in front of it.
     */
    @Test
    fun `the research prompt says not recognising the subject belongs on the plan`() =
        runTest(dispatcher) {
            loadModel()

            viewModel.startResearch("who is alpha romer coma")
            settle()

            // Not `.last()`: the fallback plan below means this run does not stop at
            // planning any more, so the planning turn is found by what it contains rather
            // than assumed to be wherever the engine happened to stop.
            val planningTurn = engine.prompts.first { convo ->
                convo.any { it.text.contains("Break this into a short numbered list") }
            }
            assertThat(planningTurn.last().text).contains("not a reason to ask first")
        }

    /**
     * The instruction alone was measured not to be enough: asked not to, the model asked who
     * Alpha Romer was anyway. This is the fix that actually holds — the tool is not offered
     * during a research brief's planning turn at all, so there is nothing for a small model
     * to reach for instead of writing the question down.
     */
    @Test
    fun `ask_user is not offered during a research planning turn`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true

        viewModel.startResearch("who is alpha romer coma")
        settle()

        assertThat(turns.asking.offered).isFalse()
    }

    /** The same tool, left alone for a goal: an action on the user's own files can be worth
     * a genuine question before anything runs, which is what plan mode's own tuning is for.
     */
    @Test
    fun `ask_user is still offered during a goal's planning turn`() = runTest(dispatcher) {
        loadModel()
        engine.hold = true

        viewModel.startGoal("Tidy the notes folder")
        settle()

        assertThat(turns.asking.offered).isTrue()
    }

    /**
     * The exact shape reported live: a research step answered "I don't have the current
     * information stored" instead of searching. The configured tool prompt defaults to
     * "you already know the answer to most questions", which argues with a turn whose whole
     * point is that the plan already decided the answer was not known.
     */
    @Test
    fun `a research step's own turn overrides the tool prompt to push searching`() =
        runTest(dispatcher) {
            loadModel()
            plans.propose("1. Find what changed\n2. Write it up")

            viewModel.startResearch("What changed in Android 16 for foreground services?")
            awaitGoalSettled()

            val stepTurn = engine.prompts.first { convo ->
                convo.any { it.text.contains("Research this one question") }
            }
            val system = stepTurn.first { it.role == ChatRole.SYSTEM }.text
            assertThat(system).contains("This step exists to search")
            assertThat(system).doesNotContain("Do not search to double check")
        }

    /**
     * The exact shape reported live, three times in a row on a physical device: asked to
     * plan a subject it did not recognise, the model answered in prose — "I don't have the
     * current information stored" — rather than proposing anything, tool or no tool. This
     * is the fallback that makes that not the end of the run: research plans one step, the
     * question exactly as asked, rather than halting before it searched even once.
     */
    @Test
    fun `research falls back to a one-step plan of the question itself rather than halting`() =
        runTest(dispatcher) {
            loadModel()

            viewModel.startResearch("who is alpha romer coma")
            awaitGoalSettled()

            assertThat(goals.goal.value?.note).isNotEqualTo(
                "No plan came back, so there is nothing to work through.",
            )
            assertThat(goals.goal.value?.plan?.steps?.map { it.text })
                .containsExactly("who is alpha romer coma")
            val stepTurn = engine.prompts.first { convo ->
                convo.any { it.text.contains("Research this one question") }
            }
            assertThat(stepTurn.last().text).contains("who is alpha romer coma")
        }

    @Test
    fun `research that never actually searched halts instead of reporting it done`() =
        runTest(dispatcher) {
            // The failure this guards against is the quiet one. A small model asked to
            // research will happily answer from what it already knows and call the step
            // finished, and a run of those reads as a completed piece of research made of
            // nothing. Every step here answers without reaching a source.
            loadModel()
            plans.propose("1. Find what changed\n2. Write it up")

            viewModel.startResearch("What changed in Android 16 for foreground services?")
            awaitGoalSettled()

            val goal = goals.goal.value
            assertThat(goal?.state).isEqualTo(GoalState.HALTED)
            assertThat(goal?.note).contains("did not finish")
            // Rolled back rather than left ticked. A step the model marked done off its own
            // say-so, with nothing read, must not survive into the retry: the next attempt
            // would start at the following question and the unanswered one would be gone.
            assertThat(plans.plan.value?.steps?.none { it.done }).isTrue()
        }

    @Test
    fun `finishing a goal started from plan mode leaves auto rather than plan`() =
        runTest(dispatcher) {
            // The mode a goal's own plan comes from, and the one mode with no tools in it.
            // Restoring it verbatim after the goal finished was how a run that worked left
            // the conversation looking exactly like one that could not: nothing ran, nothing
            // on screen said why, and the only way out was already knowing to type /auto.
            loadModel()
            // Scripted as the planning turn's own reply rather than pre-seeded onto the
            // board directly: start() now clears a stale plan before that turn runs, so a
            // plan placed on the board ahead of time would not survive to be read back.
            engine.scripted += ScriptedPass("1. Do the first thing\n2. Do the second thing")
            viewModel.setMode(AgentMode.PLAN)

            viewModel.startGoal("Do a small thing")
            awaitGoalSettled()

            assertThat(goals.goal.value?.state).isEqualTo(GoalState.DONE)
            assertThat(viewModel.uiState.value.mode).isEqualTo(AgentMode.AUTO)
        }

    @Test
    fun `finishing a goal leaves a mode the user actually chose alone`() = runTest(dispatcher) {
        // Only plan is special-cased. Ask and Yolo are usable modes on their own, and a
        // goal quietly switching one of those back to Auto would be the app overriding a
        // choice nobody asked it to reconsider.
        loadModel()
        // See the test above: scripted as the reply rather than pre-seeded on the board.
        engine.scripted += ScriptedPass("1. Do the first thing\n2. Do the second thing")
        viewModel.setMode(AgentMode.YOLO)

        viewModel.startGoal("Do a small thing")
        awaitGoalSettled()

        assertThat(goals.goal.value?.state).isEqualTo(GoalState.DONE)
        assertThat(viewModel.uiState.value.mode).isEqualTo(AgentMode.YOLO)
    }

    /**
     * The exact shape codex's loop-engineering review found: what the model is pointed at is
     * exactly one step, but a plan is closed by the same `advance` tool call regardless of
     * how many times a turn calls it, so nothing stopped one turn ticking every step of a
     * two-step plan at once and reporting the whole goal finished, having genuinely worked
     * through half of it.
     */
    @Test
    fun `a turn that closes more than the one step it was given is rolled back and redone`() =
        runTest(dispatcher) {
            loadModel()
            engine.scripted += ScriptedPass("1. Do the first thing\n2. Do the second thing")
            engine.scripted += ScriptedPass(
                text = "",
                toolCalls = listOf(
                    ToolCall(id = "a", name = "advance", argumentsJson = """{"step":1}"""),
                    ToolCall(id = "b", name = "advance", argumentsJson = """{"step":2}"""),
                ),
            )

            viewModel.startGoal("Do two things")
            awaitGoalSettled()

            val goal = goals.goal.value
            assertThat(goal?.state).isEqualTo(GoalState.DONE)
            // A single tool round allowed to close both steps at once would have counted as
            // one step taken for a two-step plan. Rolled back and worked through one at a
            // time, as every other step is, counts two.
            assertThat(goal?.stepsTaken).isEqualTo(2)
        }

    /**
     * The exact shape codex's loop-engineering review found: a goal interrupted by the
     * process dying comes back HALTED so a person can review it, and restoring the last chat
     * on the next launch reopens that goal's own conversation automatically. reopen used to
     * clear any goal on the board unconditionally, which read that restoration as a switch
     * away from the goal and erased the very recovery the board had just made visible.
     */
    @Test
    fun `reopening the conversation a halted goal belongs to does not clear it`() =
        runTest(dispatcher) {
            loadModel()
            val id = chats.startConversation("recovering", "model-a")
            viewModel.openConversation(id)
            settle()
            engine.hold = true
            viewModel.startGoal("Something long")
            settle()
            assertThat(goals.goal.value?.isRunning).isTrue()

            // Stands in for the process dying and the board recovering the goal as HALTED,
            // without actually restarting the process this test runs in.
            goals.halt("Interrupted when the app stopped. Review the plan before starting again.")
            settle()

            viewModel.openConversation(id)
            settle()

            assertThat(goals.goal.value?.state).isEqualTo(GoalState.HALTED)
        }

    /** The other half of the fix above: a goal still has to give way when what is opened is
     * genuinely a different conversation than the one it belongs to.
     */
    @Test
    fun `opening a different conversation still clears a halted goal left over from another`() =
        runTest(dispatcher) {
            loadModel()
            val first = chats.startConversation("first", "model-a")
            val second = chats.startConversation("second", "model-a")
            viewModel.openConversation(first)
            settle()
            engine.hold = true
            viewModel.startGoal("Something long")
            settle()
            goals.halt("Interrupted when the app stopped. Review the plan before starting again.")
            settle()

            viewModel.openConversation(second)
            settle()

            assertThat(goals.goal.value).isNull()
        }

    /**
     * Drains until the goal stops running, or gives up.
     *
     * A goal is several turns and the gaps between them, so a fixed number of drains is a
     * guess that gets stale the moment a step gains a round trip. Bounded so that a loop
     * which genuinely never terminates fails the test rather than hanging the suite.
     */
    private fun kotlinx.coroutines.test.TestScope.awaitGoalSettled() {
        repeat(AWAIT_STEPS) {
            if (goals.goal.value?.isRunning != true) return
            settle(steps = 4)
        }
    }
}

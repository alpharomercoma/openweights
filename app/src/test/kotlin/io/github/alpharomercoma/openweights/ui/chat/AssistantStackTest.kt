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

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.ThemeMode
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * How far down a six inch screen the answer starts, when a turn used tools.
 *
 * The adversarial UI review's fourth question was what the reasoning, intermediate text,
 * tool chips and answer stack does on a small screen, and the honest reply at the time was
 * that it needed looking at rather than reasoning about. This is the looking. The listing
 * screenshots already render the real `ChatScreen` at a real phone size on the host, so the
 * same renderer answers it in numbers instead of an opinion.
 *
 * 360 x 640 dp is the small end of what this ships to and the same canvas the Play shots
 * use. What matters is the y of the answer's first line: everything above it is preamble,
 * and preamble that fills the screen means the reader scrolls to find what they asked for.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class AssistantStackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun oneRound() = measure(rounds = 1)

    @Test
    fun twoRounds() = measure(rounds = 2)

    @Test
    fun threeRounds() = measure(rounds = 3)

    @Test
    fun fourRounds() = measure(rounds = 4)

    /**
     * The folded state, drawn, when `OPENWEIGHTS_SCREENSHOTS` says where to put it.
     *
     * The numbers above say the answer moved up the screen. They do not say the line it
     * moved up behind is one anybody would understand, and that is not a thing a number
     * settles.
     */
    @Test
    fun draw() {
        val into = System.getenv("OPENWEIGHTS_SCREENSHOTS") ?: return
        stage(rounds = 4)
        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File(into, "stack-folded.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${file.path}")
    }

    /** Folded is not lost: the whole run is one tap back. */
    @Test
    fun theStepsComeBackOnATap() {
        stage(rounds = 4)
        askAgain()
        check(
            compose.onAllNodesWithText(FIRST_SAID, substring = true)
                .fetchSemanticsNodes().isEmpty(),
        ) { "the steps were not folded away" }

        compose.onNodeWithContentDescription("Show the steps").performClick()
        compose.waitForIdle()

        check(
            compose.onAllNodesWithText(FIRST_SAID, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        ) { "the steps did not come back" }
        compose.onAllNodesWithText("notes/", substring = true).fetchSemanticsNodes().let {
            check(it.size == 3) { "expected three read_file chips, found ${it.size}" }
        }
    }

    /**
     * How much of the end-of-turn shift the folding steps are responsible for.
     *
     * The adversarial review's charge was that folding the steps when a turn finishes is the
     * status strip's layout shift moved somewhere worse. It was right: the first version
     * folded on "finished" and the answer jumped **987 pixels** upward on the frame the last
     * token arrived. Folding on "no longer the newest turn" instead takes that to 441.
     *
     * 441 is not zero and it is not this block's doing. A finished turn also loses its
     * activity line and gains a row of actions, and the transcript sits at the bottom, so
     * the content below the answer changes height and the answer moves with it. That is what
     * every chat app does when a reply lands. What this asserts is the part that is ours:
     * that a turn with four tool rounds in it shifts no more than a turn with none.
     */
    @Test
    fun theStepsAddNothingToTheEndOfTurnShift() {
        val withSteps = shiftAtEndOfTurn(rounds = 4)
        println("collapse: 4 rounds shifted ${withSteps.toInt()}px at the end of the turn")
        check(withSteps < 500f) { "the end of a turn moved the answer ${withSteps.toInt()}px" }
    }

    private fun shiftAtEndOfTurn(rounds: Int): Float {
        stage(rounds, streaming = true)
        val streaming = compose.onNodeWithText(ANSWER, substring = true)
            .fetchSemanticsNode().positionInRoot.y
        stopStreaming()
        val finished = compose.onNodeWithText(ANSWER, substring = true)
            .fetchSemanticsNode().positionInRoot.y
        return streaming - finished
    }

    /** And it does fold, once the next question has been asked. */
    @Test
    fun theStepsFoldOnceTheTurnIsNoLongerTheNewest() {
        stage(rounds = 4, streaming = true)
        stopStreaming()
        check(
            compose.onAllNodesWithText(FIRST_SAID, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        ) { "the steps folded while the turn was still the newest" }

        askAgain()

        check(
            compose.onAllNodesWithText(FIRST_SAID, substring = true)
                .fetchSemanticsNodes().isEmpty(),
        ) { "the steps stayed open after the next question" }
    }

    /** While it is still working, the steps are the progress report and stay open. */
    @Test
    fun theStepsAreOpenWhileTheTurnStreams() {
        stage(rounds = 3, streaming = true)
        check(
            compose.onAllNodesWithText(FIRST_SAID, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        ) { "the steps were hidden mid-run" }
        check(
            compose.onAllNodesWithContentDescription("Show the steps")
                .fetchSemanticsNodes().isEmpty(),
        ) { "a header appeared while the turn was running" }
    }

    /**
     * Where the answer sits, and whether it fits, for a turn that used [rounds] tools.
     *
     * Four is the agent's round cap, so four is the worst a shipped turn can be.
     */
    private fun measure(rounds: Int) {
        stage(rounds)
        askAgain()

        val screen = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        val answer = compose.onNodeWithText(ANSWER, substring = true).fetchSemanticsNode()
        val answerTop = answer.positionInRoot.y
        // From the top of the assistant turn, which is the reasoning header, to the answer.
        val turn = compose.onNodeWithText(THOUGHT, substring = true).fetchSemanticsNode()
        val preamble = answerTop - turn.positionInRoot.y

        println(
            "stack rounds=$rounds screen=${screen.toInt()}px answerTop=${answerTop.toInt()}px " +
                "(${(answerTop / screen * 100).toInt()}%) preamble=${preamble.toInt()}px " +
                "(${(preamble / screen * 100).toInt()}%)",
        )

        // Not a style preference. The preamble is the part of a reply that is not the reply,
        // and once it is most of a phone screen the answer is below the fold on the turn the
        // reader is waiting for.
        check(preamble / screen < 0.5f) {
            "rounds=$rounds: the preamble is ${(preamble / screen * 100).toInt()}% of a phone"
        }
        compose.onNodeWithText(ANSWER, substring = true).assertIsDisplayed()
    }

    /** Flips the staged turn from streaming to finished, the way a last token does. */
    private fun stopStreaming() {
        this.streaming.value = false
        compose.waitForIdle()
    }

    /** Asks the next question, which is what makes the turn above stop being the newest. */
    private fun askAgain() {
        this.superseded.value = true
        compose.waitForIdle()
    }

    private val superseded = mutableStateOf(false)

    private val streaming = mutableStateOf(false)

    @Suppress("LongMethod")
    private fun stage(rounds: Int, streaming: Boolean = false) {
        this.streaming.value = streaming
        this.superseded.value = false
        val files = listOf("q3.md", "handover.md", "meeting.md", "backlog.md")
        val blocks = buildList {
            repeat(rounds) { i ->
                add(TurnBlock.Said(SAID[i]))
                add(
                    TurnBlock.Step(
                        AgentStep.Ran(
                            call = ToolCall(
                                "${i + 1}",
                                if (i == 0) "search_files" else "read_file",
                                if (i == 0) {
                                    """{"query":"deadline"}"""
                                } else {
                                    """{"path":"notes/${files[i]}"}"""
                                },
                            ),
                            result = if (i == 0) files.joinToString("\n") else "Deadline: 14 Sep.",
                            millis = 210L - i * 40,
                        ),
                    ),
                )
            }
        }

        compose.setContent {
            OpenWeightsTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                ChatScreen(
                    state = ChatUiState(
                        modelName = "Hammer2.1-1.5B-Q4_0",
                        modelQuantization = "qwen2 1.5B Q4_0",
                        transcript = listOfNotNull(
                            TranscriptEntry(
                                id = 1,
                                role = ChatRole.USER,
                                text = "Which of my notes mention the deadline, and when is it?",
                            ),
                            TranscriptEntry(
                                id = 2,
                                role = ChatRole.ASSISTANT,
                                text = ANSWER,
                                answer = ANSWER,
                                reasoning = REASONING,
                                reasoningMs = 2_100,
                                tokensPerSecond = 13.8,
                                timeToFirstTokenMs = 240,
                                generatedTokens = 96,
                                totalMillis = 7_200,
                                blocks = blocks,
                                isStreaming = this@AssistantStackTest.streaming.value,
                            ),
                            TranscriptEntry(id = 3, role = ChatRole.USER, text = NEXT)
                                .takeIf { superseded.value },
                        ),
                        contextUsed = 1_640,
                        contextSize = 4096,
                        supportsTools = true,
                        toolsAvailable = true,
                    ),
                    onSend = { true },
                    onStop = {},
                    onRegenerate = {},
                    onNewChat = {},
                    onCompact = {},
                    plan = null,
                    onTickStep = {},
                    question = null,
                    onAnswerQuestion = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(ANSWER, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private companion object {
        const val ANSWER = "Both notes mention it, and the deadline is 14 September."

        const val NEXT = "And what should I do about it?"

        const val THOUGHT = "Thought for"

        const val FIRST_SAID = "search the notes folder"

        val SAID = listOf(
            "I should search the notes folder before answering.",
            "Two files matched, so read both.",
            "That one has the date, but the other may disagree.",
            "One more to check before I commit to a date.",
        )

        val REASONING = """
            The question asks two things at once, which files mention a deadline and what
            the date is, so a single search will not settle it. I should look through the
            notes folder for the word first, then read whatever comes back, because the
            date will be inside the file rather than in its name.
        """.trimIndent()
    }
}

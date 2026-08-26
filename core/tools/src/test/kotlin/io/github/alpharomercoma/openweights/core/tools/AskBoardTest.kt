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

package io.github.alpharomercoma.openweights.core.tools

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A tool that waits for a person.
 *
 * Everything here is about what happens when the model gets the shape wrong, because that is
 * the common case at this size and because the value of asking a question must not depend on
 * a 1B model producing a clean JSON array. A question with a broken options list is still a
 * question; a question with no options is still a question.
 */
class AskBoardTest {
    private val board = AskBoard().apply { offered = true }
    private val ask = AskUserTool(board)

    private fun call(arguments: String) = ToolCall("1", "ask_user", arguments)

    @Test
    fun `the question reaches the screen and the answer reaches the model`() = runTest {
        var result: String? = null
        launch {
            result = ask.run(
                call("""{"question":"Which folder?","options":["Notes","Documents"]}"""),
            )
        }
        // Let the tool get as far as waiting, which is the state the card is drawn from.
        runCurrent()

        assertThat(board.pending.value?.text).isEqualTo("Which folder?")
        assertThat(board.pending.value?.options).containsExactly("Notes", "Documents").inOrder()

        board.answer("Notes")
        runCurrent()

        assertThat(result).isEqualTo("Notes")
        // And the card comes down, or the next turn opens with a question already answered.
        assertThat(board.pending.value).isNull()
    }

    @Test
    fun `a broken options list still asks the question`() = runTest {
        launch { ask.run(call("""{"question":"Which folder?","options":"Notes and Documents"}""")) }
        runCurrent()

        assertThat(board.pending.value?.text).isEqualTo("Which folder?")
        assertThat(board.pending.value?.options).isEmpty()

        board.answer("either")
    }

    @Test
    fun `more than four options is a menu, so it is cut to four`() = runTest {
        val many = (1..9).joinToString(",") { "\"option $it\"" }
        launch { ask.run(call("""{"question":"Which?","options":[$many]}""")) }
        runCurrent()

        assertThat(board.pending.value?.options).hasSize(4)

        board.answer("option 1")
    }

    @Test
    fun `no question at all asks for one rather than showing an empty card`() = runTest {
        val said = ask.run(call("""{"options":["yes","no"]}"""))

        assertThat(said).contains("No question was given")
        assertThat(board.pending.value).isNull()
    }

    @Test
    fun `an empty answer tells the model to decide rather than leaving it waiting`() = runTest {
        var result: String? = null
        launch { result = ask.run(call("""{"question":"Which folder?"}""")) }
        runCurrent()

        board.answer("")
        runCurrent()

        assertThat(result).contains("Choose for them")
    }

    @Test
    fun `cancelling a pending question resolves it the same way an empty answer does`() = runTest {
        var result: String? = null
        launch { result = ask.run(call("""{"question":"Which folder?"}""")) }
        runCurrent()
        assertThat(board.pending.value).isNotNull()

        board.cancel()
        runCurrent()

        assertThat(result).contains("Choose for them")
        assertThat(board.pending.value).isNull()
    }

    @Test
    fun `cancelling with nothing pending does nothing`() {
        // A goal that stops, or a chat left for another one, calls this whether or not a
        // question happened to be open. It must be a no-op the rest of the time.
        board.cancel()
    }

    @Test
    fun `the tool is invisible unless the mode has opened it`() {
        // A fourth tool on every routing decision costs tokens once and accuracy again. This
        // one is only useful while deciding what to do, so it is only there then.
        assertThat(AskUserTool(AskBoard()).isAvailable).isFalse()
        assertThat(ask.isAvailable).isTrue()
    }
}

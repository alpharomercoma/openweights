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
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The transcript rewritten behind the screen: an edit, a regenerate, a fold.
 *
 * Each of these drops or replaces turns the user can already see, and each has to keep
 * three things in step while it does: the screen, the table, and the tool notes the
 * dropped turns had added. Apart from [ChatConversationsTest], which is at detekt's size
 * limit, and because they are one subject: what the conversation is after part of it
 * has been taken back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatRewriteTest : ChatFixture() {
    @Test
    fun `an edited question's abandoned tool results do not keep grounding the conversation`() =
        runTest(dispatcher) {
            // The sibling of the regenerate case above. An edit drops the same turns from
            // the screen and from storage, and it dropped their notes from neither: a page
            // the user had edited away rode into every prompt after it until the next reopen.
            engine.supportsTools = true
            loadModel()
            engine.scripted += ScriptedPass(
                text = "Looking.",
                toolCalls = listOf(
                    ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"x"}"""),
                ),
            )
            engine.scripted += ScriptedPass("Ada Lovelace wrote the first algorithm.")
            viewModel.send("Who is Ada Lovelace?")
            settle(steps = FOLD_SETTLE_STEPS)
            assertThat(viewModel.uiState.value.toolNotes.render()).contains("Ada Lovelace")

            engine.scripted += ScriptedPass("She was a mathematician.")
            val question = viewModel.uiState.value.transcript.first { it.role == ChatRole.USER }
            viewModel.editAndResend(question.id, "Who was Ada Lovelace, in one line?")
            settle(steps = FOLD_SETTLE_STEPS)

            assertThat(viewModel.uiState.value.toolNotes.render().orEmpty())
                .doesNotContain("Ada Lovelace")
        }

    @Test
    fun `a reply that could not be discarded stays on screen`() = runTest(dispatcher) {
        // Regenerate drops the reply from the screen before the delete, and when the delete
        // failed it gave the composer back and nothing else: the screen showed a question
        // with no answer, storage still held one, and the two disagreed until the next
        // reopen. An edit that fails the same way already puts its turns back.
        loadModel()
        viewModel.send("Who was Ada Lovelace?")
        settle()
        val before = viewModel.uiState.value.transcript
        writer.broken = true

        viewModel.regenerate()
        settle()

        assertThat(viewModel.uiState.value.transcript).isEqualTo(before)
        assertThat(viewModel.uiState.value.isGenerating).isFalse()
        assertThat(viewModel.uiState.value.error).contains("could not be saved")
    }

    @Test
    fun `a question storage never took cannot be edited into another question's row`() =
        runTest(dispatcher) {
            // A send whose write fails is answered anyway, so from then on the transcript
            // runs one turn ahead of the table. An edit that found its row by position then
            // rewrote the question after the one that was tapped.
            loadModel()
            writer.broken = true
            viewModel.send("First")
            settle()
            writer.broken = false
            viewModel.send("Second")
            settle()
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)
            awaitMessages(id, count = 2)
            val shown = viewModel.uiState.value.transcript

            viewModel.editAndResend(shown.first().id, "First, reworded")
            settle()

            // Refused with the sentence the failed write already showed, and nothing moved.
            assertThat(viewModel.uiState.value.error).contains("could not be saved")
            assertThat(viewModel.uiState.value.transcript).isEqualTo(shown)
            assertThat(chats.messages(id).first().text).isEqualTo("Second")
        }

    @Test
    fun `an edit finds its row when the transcript has run ahead of the table`() =
        runTest(dispatcher) {
            // The other half of the same shape: the question that *was* stored sits at
            // transcript index two and table index zero, and by position there was no row
            // there to rewrite.
            loadModel()
            writer.broken = true
            viewModel.send("First")
            settle()
            writer.broken = false
            viewModel.send("Second")
            settle()
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)
            awaitMessages(id, count = 2)
            val second = viewModel.uiState.value.transcript.last { it.role == ChatRole.USER }

            viewModel.editAndResend(second.id, "Second, reworded")
            settle()

            assertThat(viewModel.uiState.value.error).isNull()
            assertThat(chats.messages(id).first().text).isEqualTo("Second, reworded")
            assertThat(viewModel.uiState.value.transcript.last { it.role == ChatRole.USER }.text)
                .isEqualTo("Second, reworded")
        }

    @Test
    fun `the composer stays closed until a fold is applied, not only until it is written`() =
        runTest(dispatcher) {
            // The busy flag dropped the moment the summary was written, and the fold then
            // suspended on its own write and on the update that rewrites the transcript. A
            // turn settles with the composer free before it folds, so in that window Send
            // passed and a second turn opened against the prompt the fold was rewriting.
            loadModel()
            repeat(4) { index ->
                viewModel.send("Question $index")
                settle()
            }
            settle(steps = FOLD_SETTLE_STEPS)
            val resets = engine.resetCount
            val gate = CompletableDeferred<Unit>()
            writer.held = gate

            viewModel.compactNow()
            // The summary is written and the cache reset; the fold is now parked on the
            // write that records it, which is the window the flag used to be clear in.
            settleUntil { engine.resetCount > resets }

            assertThat(viewModel.uiState.value.isCompacting).isTrue()
            assertThat(viewModel.uiState.value.canSend).isFalse()

            writer.held = null
            gate.complete(Unit)
            settle(steps = FOLD_SETTLE_STEPS)

            assertThat(viewModel.uiState.value.compaction).isNotNull()
            assertThat(viewModel.uiState.value.isCompacting).isFalse()
            assertThat(viewModel.uiState.value.canSend).isTrue()
        }
}

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The background warms that keep a rewritten prompt out of the user's wait.
 *
 * A fold, a branch and a reopen all rewrite the prompt from the root, which used to mean
 * the next question paid a full re-read in the foreground. These tests pin the contract:
 * each rewrite is followed by a conversation warm, the warm never claims the fresh-head
 * snapshot slot, and what it reads is byte-for-byte the front of the prompt the next send
 * renders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatWarmTest : ChatFixture() {

    @Test
    fun `branching warms the carried conversation instead of resetting the cache`() =
        runTest(dispatcher) {
            loadModel()
            engine.scripted += ScriptedPass("Ada Lovelace wrote the first algorithm.")
            viewModel.send("Who is Ada Lovelace?")
            settle(steps = FOLD_SETTLE_STEPS)

            engine.warmCalls.clear()
            val resetsBefore = engine.resetCount
            val lastEntry = viewModel.uiState.value.transcript.last()
            viewModel.branchFrom(lastEntry.id)
            settle(steps = FOLD_SETTLE_STEPS)

            // The parent's cache is byte-shared with the branch; clearing it would throw
            // away exactly the turns the branch is about to re-send.
            assertThat(engine.resetCount).isEqualTo(resetsBefore)
            // Head first, with the snapshot; then the carried conversation, without it.
            assertThat(engine.warmCalls.map { it.snapshot }).isEqualTo(listOf(true, false))
            val warmed = engine.warmCalls.last().messages
            assertThat(warmed.last().role).isEqualTo(ChatRole.ASSISTANT)
            assertThat(warmed.last().text).contains("Ada Lovelace wrote")
            // A branch from the last reply is exactly what the record stands for, so it
            // rides along and the branch keeps extending the parent's cache.
            assertThat(viewModel.uiState.value.engineHistory).isNotNull()
        }

    @Test
    fun `branching from an earlier turn drops the record but still warms the carried turns`() =
        runTest(dispatcher) {
            loadModel()
            engine.scripted += ScriptedPass("Ada Lovelace wrote the first algorithm.")
            viewModel.send("Who is Ada Lovelace?")
            settle(steps = FOLD_SETTLE_STEPS)
            engine.scripted += ScriptedPass("She was born in 1815.")
            viewModel.send("When was she born?")
            settle(steps = FOLD_SETTLE_STEPS)

            engine.warmCalls.clear()
            val firstReply = viewModel.uiState.value.transcript
                .first { it.role == ChatRole.ASSISTANT }
            viewModel.branchFrom(firstReply.id)
            settle(steps = FOLD_SETTLE_STEPS)

            // The record runs through the second reply, which the branch does not carry,
            // and it cannot be cut at the branch point: tool rounds mean its messages do
            // not map one-to-one onto transcript entries.
            assertThat(viewModel.uiState.value.engineHistory).isNull()
            val warmed = engine.warmCalls.last()
            assertThat(warmed.snapshot).isFalse()
            assertThat(warmed.messages.last().text).contains("Ada Lovelace wrote")
            assertThat(warmed.messages.none { it.text.contains("born in 1815") }).isTrue()
        }

    @Test
    fun `a fold warms the recap conversation it just rewrote`() = runTest(dispatcher) {
        loadModel()
        repeat(3) { turn ->
            engine.scripted += ScriptedPass("Answer number $turn, said at some length.")
            viewModel.send("Question number $turn?")
            settle(steps = FOLD_SETTLE_STEPS)
        }

        engine.warmCalls.clear()
        engine.scripted += ScriptedPass("They discussed three numbered questions.")
        viewModel.compactNow()
        settle(steps = FOLD_SETTLE_STEPS)

        assertThat(viewModel.uiState.value.compaction).isNotNull()
        // The warm reads the prompt the fold left behind: the recap turn standing in
        // for the folded exchanges, then the turns kept verbatim.
        val warmed = engine.warmCalls.last()
        assertThat(warmed.snapshot).isFalse()
        assertThat(
            warmed.messages.any { it.text.contains("Earlier in this conversation:") },
        ).isTrue()
        assertThat(
            warmed.messages.any { it.text.contains("three numbered questions") },
        ).isTrue()
    }

    @Test
    fun `the warmed conversation is byte-for-byte the front of the next prompt`() =
        runTest(dispatcher) {
            loadModel()
            engine.scripted += ScriptedPass("Ada Lovelace wrote the first algorithm.")
            viewModel.send("Who is Ada Lovelace?")
            settle(steps = FOLD_SETTLE_STEPS)

            engine.warmCalls.clear()
            viewModel.branchFrom(viewModel.uiState.value.transcript.last().id)
            settle(steps = FOLD_SETTLE_STEPS)

            engine.scripted += ScriptedPass("She was born in 1815.")
            viewModel.send("When was she born?")
            settle(steps = FOLD_SETTLE_STEPS)

            // Everything the warm read is reused by the send, and the send adds exactly
            // one thing: the question. This equality is the entire mechanism — the warm
            // is only worth its battery if these bytes match.
            val warmed = engine.warmCalls.last().messages
            val prompt = engine.prompts.last()
            warmed.forEachIndexed { index, message ->
                assertThat(prompt[index].role).isEqualTo(message.role)
                assertThat(prompt[index].text).isEqualTo(message.text)
            }
            assertThat(prompt.size).isEqualTo(warmed.size + 1)
            assertThat(prompt.last().role).isEqualTo(ChatRole.USER)
            assertThat(prompt.last().text).contains("When was she born?")
        }
}

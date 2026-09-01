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
    fun `a conversation keeps its day across midnight and a fresh chat picks up the new one`() =
        runTest(dispatcher) {
            loadModel()
            engine.scripted += ScriptedPass("Ada Lovelace wrote the first algorithm.")
            val startDay = PromptDay.pinned
            viewModel.send("Who is Ada Lovelace?")
            settle(steps = FOLD_SETTLE_STEPS)

            // Midnight passes with the process alive and a conversation open. Rendered
            // live, the date line diverged every prompt from the cache, and a hybrid
            // model paid a full foreground re-read for the next "hi" — the measured
            // incident this pins.
            val yesterday = java.time.LocalDate.now().minusDays(1)
            PromptDay.pin(yesterday)

            // A warm fired mid-conversation — a branch here — must not move the day:
            // the conversation keeps the bytes its cache holds, which is the engine
            // record replaying the first question exactly as it was sent, date and all.
            engine.warmCalls.clear()
            viewModel.branchFrom(viewModel.uiState.value.transcript.last().id)
            settle(steps = FOLD_SETTLE_STEPS)
            assertThat(PromptDay.pinned).isEqualTo(yesterday)
            // The head must stay byte-identical across midnight — that is the point of
            // moving the date out of it — while the conversation's first question keeps
            // carrying the day it started with.
            val branchWarm = engine.warmCalls.last { call ->
                call.messages.any { it.role == ChatRole.USER }
            }
            assertThat(branchWarm.messages.first().text).doesNotContain("Today is")
            assertThat(
                branchWarm.messages.first { it.role == ChatRole.USER }.text,
            ).contains("Today is $startDay.")

            // A fresh chat is a new start: its warm moves the pin and reads the new
            // head in the background, before anybody has typed.
            viewModel.newChat()
            settle(steps = FOLD_SETTLE_STEPS)
            assertThat(PromptDay.pinned).isEqualTo(java.time.LocalDate.now())
        }

    @Test
    fun `the head warm persists to a model-keyed file and the conversation warm never does`() =
        runTest(dispatcher) {
            loadModel()
            engine.scripted += ScriptedPass("Ada Lovelace wrote the first algorithm.")
            viewModel.send("Who is Ada Lovelace?")
            settle(steps = FOLD_SETTLE_STEPS)

            engine.warmCalls.clear()
            viewModel.branchFrom(viewModel.uiState.value.transcript.last().id)
            settle(steps = FOLD_SETTLE_STEPS)

            // The head's state is worth keeping across processes -- its bytes hold for a
            // day -- and the file is keyed to the exact weights. A conversation's is not:
            // it changes every turn and would churn tens of megabytes per reply.
            val head = engine.warmCalls.first { it.snapshot }
            val conversation = engine.warmCalls.first { !it.snapshot }
            assertThat(head.store).isNotNull()
            assertThat(head.store).contains("model-a.gguf")
            assertThat(head.store).endsWith(".warm")
            assertThat(conversation.store).isNull()
        }

    @Test
    fun `a question arriving mid-warm interrupts it instead of queueing behind it`() =
        runTest(dispatcher) {
            loadModel()
            settle(steps = FOLD_SETTLE_STEPS)

            // A warm that sits in its prefill the way a real one does on a struggling
            // phone. The measured incident: "hi" queued behind one for seventy seconds,
            // because a one-shot cancel had been swallowed. The contract now is a
            // standing interrupt: the turn cancels until it holds the engine.
            // Armed after newChat() returns: its own stop() fires a cancel that would
            // release the gate before the warm ever parked on it. The warm coroutine
            // only runs once the dispatcher advances, in settle below.
            viewModel.newChat()
            engine.warmGate = kotlinx.coroutines.CompletableDeferred()
            settle(steps = 2)
            val cancelsBefore = engine.cancelCount

            engine.scripted += ScriptedPass("Hello there.")
            viewModel.send("hi")
            settle(steps = FOLD_SETTLE_STEPS)

            assertThat(engine.cancelCount).isGreaterThan(cancelsBefore)
            assertThat(viewModel.uiState.value.transcript.last().text).contains("Hello there")
            engine.warmGate = null
        }

    @Test
    fun `a head warm that kept nothing does not stack a conversation warm on top`() =
        runTest(dispatcher) {
            loadModel()
            engine.scripted += ScriptedPass("Ada Lovelace wrote the first algorithm.")
            viewModel.send("Who is Ada Lovelace?")
            settle(steps = FOLD_SETTLE_STEPS)
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)
            viewModel.newChat()
            settle(steps = FOLD_SETTLE_STEPS)

            // The measured failure shape: the head warm died to a compute error after
            // 42 seconds. Stacking the longer conversation read on that engine is
            // another minute of background churn on a phone already struggling.
            engine.warmKeepsNothing = true
            engine.warmCalls.clear()
            viewModel.openConversation(id)
            settle(steps = FOLD_SETTLE_STEPS)

            assertThat(engine.warmCalls.map { it.snapshot }).isEqualTo(listOf(true))
            engine.warmKeepsNothing = false
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
            // Captured before the send: settling the next turn warms again, post-turn.
            val warmed = engine.warmCalls.last().messages

            engine.scripted += ScriptedPass("She was born in 1815.")
            viewModel.send("When was she born?")
            settle(steps = FOLD_SETTLE_STEPS)

            // Everything the warm read is reused by the send, and the send adds exactly
            // one thing: the question. This equality is the entire mechanism — the warm
            // is only worth its battery if these bytes match.
            val prompt = engine.prompts.last()
            warmed.forEachIndexed { index, message ->
                assertThat(prompt[index].role).isEqualTo(message.role)
                assertThat(prompt[index].text).isEqualTo(message.text)
            }
            assertThat(prompt.size).isEqualTo(warmed.size + 1)
            assertThat(prompt.last().role).isEqualTo(ChatRole.USER)
            assertThat(prompt.last().text).contains("When was she born?")
        }

    @Test
    fun `toggling a tool re-warms the head in the background`() = runTest(dispatcher) {
        loadModel()
        settle()

        engine.warmCalls.clear()
        switches.setEnabled(StubTool.definition.name, false)
        settle()

        // A toggle rewrites the tool block at the head of every future prompt, and for a
        // hybrid model any head change is a full re-read. Heard here, that read runs now,
        // in the background; unheard, it ran in front of the user on their next send.
        assertThat(engine.warmCalls.count { it.snapshot }).isEqualTo(1)
    }

    @Test
    fun `granting or forgetting the folder re-warms the head too`() = runTest(dispatcher) {
        loadModel()
        settle()

        engine.warmCalls.clear()
        // The file tools describe themselves to the model only while a folder is shared,
        // so the grant reshapes the prompt head exactly the way a toggle does.
        grant.forget()
        settle()

        assertThat(engine.warmCalls.count { it.snapshot }).isEqualTo(1)
    }
}

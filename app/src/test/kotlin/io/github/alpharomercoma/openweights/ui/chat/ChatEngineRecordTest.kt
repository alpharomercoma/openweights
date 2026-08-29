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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The engine-history record: the exact prompt the engine read, persisted per turn so the
 * next one byte-extends the KV cache instead of rebuilding it.
 *
 * Split from [ChatConversationsTest] by subject: everything here is about the record --
 * that it extends, survives a reopen, refuses other models, and follows deletions. The
 * harness both sit on is [ChatFixture].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatEngineRecordTest : ChatFixture() {

    @Test
    fun `the turn after a tool turn extends the engine's conversation, byte for byte`() =
        runTest(dispatcher) {
            // The whole point of the engine-history record: the next prompt begins with
            // exactly what the cache already holds -- the decorated question, the tool
            // round the template rendered, the reply as it was decoded -- and only then
            // adds the new question. Rebuilt from the transcript instead, the prompt
            // diverged at the first decoration, which a hybrid model pays as a full
            // re-read of the conversation: measured live at 1.7k tokens on the turn after
            // every tool turn.
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

            engine.scripted += ScriptedPass("She was born in 1815.")
            viewModel.send("When was she born?")
            settle(steps = FOLD_SETTLE_STEPS)

            // The last prompt of turn one is what the cache holds, minus the reply that
            // was then decoded into it.
            val cacheHeld = engine.prompts[1]
            val nextTurn = engine.prompts[2]
            cacheHeld.forEachIndexed { index, message ->
                assertThat(nextTurn[index].role).isEqualTo(message.role)
                assertThat(nextTurn[index].text).isEqualTo(message.text)
            }
            // Then the reply, exactly as decoded, then the new question -- nothing else
            // rewritten, nothing dropped.
            assertThat(nextTurn.size).isEqualTo(cacheHeld.size + 2)
            assertThat(nextTurn[cacheHeld.size].role).isEqualTo(ChatRole.ASSISTANT)
            assertThat(nextTurn[cacheHeld.size].text).contains("Ada Lovelace wrote")
            assertThat(nextTurn.last().text).contains("When was she born?")
        }

    @Test
    fun `the engine's conversation survives a reopen and is still extended, not rebuilt`() =
        runTest(dispatcher) {
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
            val id = requireNotNull(viewModel.uiState.value.activeConversationId)
            val cacheHeld = engine.prompts[1]

            viewModel.newChat()
            settle()
            viewModel.openConversation(id)
            settle(steps = FOLD_SETTLE_STEPS)

            engine.scripted += ScriptedPass("She was born in 1815.")
            viewModel.send("When was she born?")
            settle(steps = FOLD_SETTLE_STEPS)

            // The cache is cold after a reopen, but the *record* is not: the next prompt
            // still replays the tool round exactly, so one prefill re-warms the cache and
            // every turn after that extends it. Without the stored record, the tool round
            // vanished from history on reopen and the model lost what it had looked up.
            val nextTurn = engine.prompts[2]
            cacheHeld.forEachIndexed { index, message ->
                assertThat(nextTurn[index].role).isEqualTo(message.role)
                assertThat(nextTurn[index].text).isEqualTo(message.text)
            }
            assertThat(nextTurn.size).isEqualTo(cacheHeld.size + 2)
        }

    @Test
    fun `a question stopped before its reply does not break the record's alternation`() =
        runTest(dispatcher) {
            // The record path bypasses asExchange on purpose, so the joining that used to
            // absorb a dangling question — one whose reply was stopped before its first
            // token — has to happen on the tail here instead. Unjoined, the prompt carried
            // two user turns in a row and the strict templates refuse to render that.
            engine.supportsTools = true
            loadModel()
            engine.scripted += ScriptedPass(
                text = "Looking.",
                toolCalls = listOf(
                    ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"x"}"""),
                ),
            )
            engine.scripted += ScriptedPass("Answer one.")
            viewModel.send("First question")
            settle(steps = FOLD_SETTLE_STEPS)

            engine.hold = true
            viewModel.send("Second question")
            settle()
            viewModel.stop()
            settle()
            engine.hold = false

            engine.scripted += ScriptedPass("Answer three.")
            viewModel.send("Third question")
            settle(steps = FOLD_SETTLE_STEPS)

            val prompt = engine.prompts.last()
            prompt.zipWithNext().forEach { (before, after) ->
                if (before.role != ChatRole.TOOL && after.role != ChatRole.TOOL) {
                    assertThat(before.role).isNotEqualTo(after.role)
                }
            }
            // Joined, not dropped: both questions are still in the prompt.
            val text = prompt.joinToString { it.text }
            assertThat(text).contains("Second question")
            assertThat(text).contains("Third question")
        }

    @Test
    fun `the engine's record does not follow the conversation to a different model`() =
        runTest(dispatcher) {
            // The record holds one template's own rendering of tool rounds — raw call
            // syntax, tool-result roles. Replayed into another model's template that is
            // foreign control text at best and a render refusal at worst, so a model
            // switch falls the prompt back to the plain transcript.
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

            assertThat(viewModel.uiState.value.engineHistory).isNotNull()
            loadModel(name = "model-b.gguf", keepConversation = true)
            settle(steps = FOLD_SETTLE_STEPS)
            assertThat(viewModel.uiState.value.engineHistory).isNull()

            engine.scripted += ScriptedPass("She was born in 1815.")
            viewModel.send("When was she born?")
            settle(steps = FOLD_SETTLE_STEPS)

            // The fallback prompt keeps the conversation but not the tool round: the
            // "Looking." pass belonged to the other model's template.
            val prompt = engine.prompts.last()
            val text = prompt.joinToString { it.text }
            assertThat(text).contains("Who is Ada Lovelace?")
            assertThat(text).doesNotContain("Looking.")
        }

    @Test
    fun `a regenerated reply does not leave the engine's record claiming the old one`() =
        runTest(dispatcher) {
            engine.supportsTools = true
            loadModel()
            engine.scripted += ScriptedPass(
                text = "Looking.",
                toolCalls = listOf(
                    ToolCall(id = "1", name = "web_search", argumentsJson = """{"query":"x"}"""),
                ),
            )
            engine.scripted += ScriptedPass("First answer.")
            viewModel.send("Who is Ada Lovelace?")
            settle(steps = FOLD_SETTLE_STEPS)

            engine.scripted += ScriptedPass("Second answer.")
            viewModel.regenerate()
            settle(steps = FOLD_SETTLE_STEPS)

            // The regenerated turn was built from the transcript -- the record named a
            // reply that no longer exists -- and its own record now runs through the
            // replacement, so the next turn extends the conversation that actually stands.
            engine.scripted += ScriptedPass("A follow-up answer.")
            viewModel.send("And when was she born?")
            settle(steps = FOLD_SETTLE_STEPS)

            val followUp = engine.prompts.last()
            assertThat(followUp.map { it.text }.joinToString()).contains("Second answer.")
            assertThat(followUp.map { it.text }.joinToString()).doesNotContain("First answer.")
        }
}

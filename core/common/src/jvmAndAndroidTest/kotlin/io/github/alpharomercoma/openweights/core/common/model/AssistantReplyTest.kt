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

package io.github.alpharomercoma.openweights.core.common.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AssistantReplyTest {
    @Test
    fun `plain output has no reasoning`() {
        val reply = parseAssistantReply("A KV cache stores past attention tensors.")

        assertThat(reply.reasoning).isNull()
        assertThat(reply.answer).isEqualTo("A KV cache stores past attention tensors.")
        assertThat(reply.isReasoningInProgress).isFalse()
    }

    @Test
    fun `separates a closed reasoning block from the answer`() {
        val reply =
            parseAssistantReply(
                "<think>\nThe user wants a definition.\n</think>\nIt caches keys and values.",
            )

        assertThat(reply.reasoning).isEqualTo("The user wants a definition.")
        assertThat(reply.answer).isEqualTo("It caches keys and values.")
        assertThat(reply.isReasoningInProgress).isFalse()
    }

    @Test
    fun `reports reasoning still in progress while the block is open`() {
        // This is what the UI sees on every token until the model closes the block; it must
        // not render half a thought as if it were the answer.
        val reply = parseAssistantReply("<think>Let me work through")

        assertThat(reply.reasoning).isEqualTo("Let me work through")
        assertThat(reply.answer).isEmpty()
        assertThat(reply.isReasoningInProgress).isTrue()
    }

    @Test
    fun `treats a lone closing tag as a template-opened block`() {
        // Some chat templates emit <think> themselves, so the model's output starts inside
        // the block and only ever produces the closing tag.
        val reply = parseAssistantReply("Working it out.\n</think>\nHere is the answer.")

        assertThat(reply.reasoning).isEqualTo("Working it out.")
        assertThat(reply.answer).isEqualTo("Here is the answer.")
        assertThat(reply.isReasoningInProgress).isFalse()
    }

    @Test
    fun `keeps text emitted before the reasoning block`() {
        val reply = parseAssistantReply("Sure. <think>trivial</think> Done.")

        assertThat(reply.reasoning).isEqualTo("trivial")
        assertThat(reply.answer).isEqualTo("Sure.  Done.")
    }

    @Test
    fun `empty output is an empty answer`() {
        val reply = parseAssistantReply("")

        assertThat(reply.reasoning).isNull()
        assertThat(reply.answer).isEmpty()
    }

    @Test
    fun `a stray opening tag after the close does not take the reply down`() {
        // The template opens the block, so the stream starts mid-thought and closes it. If
        // the answer then mentions the tag, the close comes before the open and the two
        // indexes cross. This used to compute substring(29, 9) and throw, in a coroutine
        // with no catch above it on the conversation reload path: the chat became
        // impossible to open, and the process went with it.
        val reply = parseAssistantReply("weighing it</think>Wrap thoughts in <think> tags.")

        assertThat(reply.reasoning).isEqualTo("weighing it")
        assertThat(reply.answer).isEqualTo("Wrap thoughts in <think> tags.")
        assertThat(reply.isReasoningInProgress).isFalse()
    }

    @Test
    fun `a close tag inside the answer is left where the model put it`() {
        // The other crossing: an ordinary block, and then the closing tag written again as
        // text. The first block is the reasoning and the rest is the answer, whatever it
        // happens to contain.
        val reply = parseAssistantReply("<think>short</think>Say </think> to end it.")

        assertThat(reply.reasoning).isEqualTo("short")
        assertThat(reply.answer).isEqualTo("Say </think> to end it.")
    }

    @Test
    fun `an unterminated second thought is still reasoning in progress`() {
        val reply = parseAssistantReply("<think>first</think>answer<think>second")

        assertThat(reply.reasoning).isEqualTo("first")
        assertThat(reply.answer).isEqualTo("answer<think>second")
    }
}

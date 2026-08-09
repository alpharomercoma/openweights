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

/**
 * A model reply split into the part meant for the reader and the part the model was
 * talking to itself.
 *
 * @param reasoning the chain of thought, or null if the model emitted none.
 * @param answer everything outside the reasoning block.
 * @param isReasoningInProgress true while a reasoning block has opened but not closed, so
 *   the UI can say "thinking" rather than showing an empty answer.
 */
data class AssistantReply(
    val reasoning: String?,
    val answer: String,
    val isReasoningInProgress: Boolean,
)

/**
 * Splits raw model output into reasoning and answer.
 *
 * Reasoning models such as LFM2.5, Qwen3, and DeepSeek-R1 wrap their chain of thought in
 * `<think>` tags. Some chat templates pre-fill the opening tag, so output can arrive with a
 * closing tag and no opening one; that case is treated as "everything up to `</think>` was
 * reasoning", which is what those templates mean.
 *
 * This runs on every streamed token, so it stays a single scan with no regex.
 */
fun parseAssistantReply(raw: String): AssistantReply {
    val openIndex = raw.indexOf(OPEN_TAG)
    val closeIndex = raw.indexOf(CLOSE_TAG)

    // No reasoning markers at all: the whole thing is the answer.
    if (openIndex < 0 && closeIndex < 0) {
        return AssistantReply(reasoning = null, answer = raw, isReasoningInProgress = false)
    }

    // Closing tag with no opening tag: the template opened the block for the model.
    if (openIndex < 0) {
        return AssistantReply(
            reasoning = raw.take(closeIndex).trim(),
            answer = raw.substring(closeIndex + CLOSE_TAG.length).removePrefix("\n"),
            isReasoningInProgress = false,
        )
    }

    val prelude = raw.take(openIndex)

    // Opening tag still unclosed: everything after it is reasoning so far.
    if (closeIndex < 0) {
        return AssistantReply(
            reasoning = raw.substring(openIndex + OPEN_TAG.length).trim(),
            answer = prelude,
            isReasoningInProgress = true,
        )
    }

    return AssistantReply(
        reasoning = raw.substring(openIndex + OPEN_TAG.length, closeIndex).trim(),
        answer = (prelude + raw.substring(closeIndex + CLOSE_TAG.length)).removePrefix("\n"),
        isReasoningInProgress = false,
    )
}

private const val OPEN_TAG = "<think>"
private const val CLOSE_TAG = "</think>"

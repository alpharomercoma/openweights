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
 * Renders a conversation the way Phi-4-mini's own chat template does.
 *
 * A transcription of `microsoft/Phi-4-mini-instruct`'s `chat_template`, proved
 * byte-for-byte by `Phi4PromptTest`. Every turn is `<|role|>content<|end|>` with no
 * newlines between turns, and tools ride on the system turn as a JSON array between
 * `<|tool|>` markers — a field of that message upstream, a parameter here, so a
 * conversation with tools and no system message gets an empty system turn to carry them.
 */
object Phi4Prompt {

    fun render(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        addGenerationPrompt: Boolean = true,
    ): String = buildString {
        val leadingSystem = messages.firstOrNull()?.takeIf { it.role == ChatRole.SYSTEM }
        if (tools.isNotEmpty()) {
            append("<|system|>").append(leadingSystem?.text.orEmpty())
            append("<|tool|>[")
            tools.forEachIndexed { index, tool ->
                if (index > 0) append(", ")
                append(tool.asToolJson())
            }
            append("]<|/tool|>").append("<|end|>")
        } else if (leadingSystem != null) {
            append("<|system|>").append(leadingSystem.text).append("<|end|>")
        }

        messages.forEachIndexed { index, message ->
            if (index == 0 && leadingSystem != null) return@forEachIndexed
            append("<|").append(message.role.wireName).append("|>")
            append(message.text).append("<|end|>")
        }

        if (addGenerationPrompt) append("<|assistant|>")
    }
}

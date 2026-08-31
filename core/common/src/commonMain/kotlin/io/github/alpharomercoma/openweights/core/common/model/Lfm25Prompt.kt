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
 * Renders a conversation the way LFM 2.5's own chat template does.
 *
 * A transcription of `LiquidAI/LFM2.5-1.2B-Instruct`'s `chat_template.jinja`, proved
 * byte-for-byte by `Lfm25PromptTest`. ChatML turns with no default system prompt; tools
 * are listed inside the system turn as `List of tools: [...]`; calls go out pythonic
 * between `<|tool_call_start|>` markers and results come back under a `tool` role.
 *
 * The template keeps `<think>` reasoning only in the last assistant turn and strips it
 * from every earlier one — the same shape as Qwen3, and the same problem for a runtime
 * whose KV cache holds what was actually generated, so the same [verbatimHistory] escape
 * hatch exists and the engine uses it.
 *
 * @param includeBos whether to open with `<|startoftext|>`. The fixtures carry it; the
 * engine renders without, because ExecuTorch's runner arms BOS itself after every reset.
 */
object Lfm25Prompt {

    @Suppress("LongParameterList")
    fun render(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        addGenerationPrompt: Boolean = true,
        verbatimHistory: Boolean = false,
        includeBos: Boolean = true,
    ): String = buildString {
        if (includeBos) append("<|startoftext|>")

        val leadingSystem = messages.firstOrNull()?.takeIf { it.role == ChatRole.SYSTEM }
        val turns = if (leadingSystem != null) messages.drop(1) else messages

        val system = buildString {
            append(leadingSystem?.text.orEmpty())
            if (tools.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("List of tools: [")
                tools.forEachIndexed { index, tool ->
                    if (index > 0) append(", ")
                    append(tool.asToolJson())
                }
                append(']')
            }
        }
        if (system.isNotEmpty()) {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        }

        val lastAssistant = turns.indexOfLast { it.role == ChatRole.ASSISTANT }
        turns.forEachIndexed { index, message ->
            append("<|im_start|>").append(message.role.wireName).append('\n')
            val text = message.text
            val keepThinking = verbatimHistory || index == lastAssistant
            if (message.role == ChatRole.ASSISTANT && !keepThinking && THINK_CLOSE in text) {
                // Trimmed only when something was actually stripped — the template's rule.
                append(text.substringAfterLast(THINK_CLOSE).trim())
            } else {
                append(text)
            }
            append("<|im_end|>\n")
        }

        if (addGenerationPrompt) append("<|im_start|>assistant\n")
    }

    private const val THINK_CLOSE = "</think>"
}

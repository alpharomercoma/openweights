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
 * Renders a conversation the way SmolLM2's own chat template does.
 *
 * A transcription of `HuggingFaceTB/SmolLM2-1.7B-Instruct`'s `chat_template`, proved
 * byte-for-byte by `SmolLm2PromptTest` against `tools/executorch/render_reference.py`.
 * The simplest template of the catalogue: ChatML turns, no tools, no thinking, and one
 * behaviour worth naming — a conversation that does not open with a system message gets
 * the model's own default one.
 */
object SmolLm2Prompt {

    fun render(messages: List<ChatMessage>, addGenerationPrompt: Boolean = true): String =
        buildString {
            if (messages.firstOrNull()?.role != ChatRole.SYSTEM) {
                appendBlock("system", DEFAULT_SYSTEM)
            }
            // Every message renders under its own role, wherever it sits: a later system
            // message is an ordinary turn, and a tool result renders under "tool" because
            // that is what the template does with a role it never heard of.
            messages.forEach { appendBlock(it.role.wireName, it.text) }
            if (addGenerationPrompt) append("<|im_start|>assistant\n")
        }

    private fun StringBuilder.appendBlock(role: String, content: String) {
        append("<|im_start|>").append(role).append('\n').append(content).append("<|im_end|>\n")
    }

    private const val DEFAULT_SYSTEM =
        "You are a helpful AI assistant named SmolLM, trained by Hugging Face"
}

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
 * Renders a conversation the way Llama 3.2's own chat template does.
 *
 * A transcription of `meta-llama/Llama-3.2-1B-Instruct`'s `chat_template`, proved
 * byte-for-byte by `Llama32PromptTest`. A system block always opens the prompt, carrying
 * a knowledge cutoff and today's date; tool schemas ride in the first user turn, indented
 * four deep; calls come back as bare JSON and results are fed under an `ipython` header,
 * JSON-quoted when they are plain text.
 *
 * @param date today, in Llama's own spelling — a parameter so the tests can pin it.
 * @param includeBos whether to open with `<|begin_of_text|>`. The fixtures carry it, so
 * the tests render with it; the engine renders without, because ExecuTorch's runner arms
 * BOS itself after every reset and a second one would be a token the model never trains on.
 */
object Llama32Prompt {

    @Suppress("LongParameterList")
    fun render(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        addGenerationPrompt: Boolean = true,
        date: String = promptDateToday().asLlamaDate(),
        includeBos: Boolean = true,
        /**
         * Emit assistant turns exactly as stored instead of trimmed. The template's trim
         * is cosmetic; the KV cache's memory of what was generated is not, and a reply
         * that happened to end in a newline was being re-rendered without it, which made
         * the next prompt no longer an extension of the cache (codex QA).
         */
        verbatimHistory: Boolean = false,
    ): String = buildString {
        if (includeBos) append("<|begin_of_text|>")

        val leadingSystem = messages.firstOrNull()?.takeIf { it.role == ChatRole.SYSTEM }
        var remaining = if (leadingSystem != null) messages.drop(1) else messages

        append("<|start_header_id|>system<|end_header_id|>\n\n")
        if (tools.isNotEmpty()) append("Environment: ipython\n")
        append("Cutting Knowledge Date: December 2023\n")
        append("Today Date: ").append(date).append("\n\n")
        append(leadingSystem?.text?.trim().orEmpty())
        append("<|eot_id|>")

        // Tools travel in the first user turn rather than the system block — the
        // template's default (`tools_in_user_message`), kept because it is also the
        // spelling Meta's own examples train against.
        if (tools.isNotEmpty() && remaining.isNotEmpty()) {
            val firstUser = remaining.first()
            remaining = remaining.drop(1)
            append("<|start_header_id|>user<|end_header_id|>\n\n")
            append(TOOLS_GUIDANCE)
            tools.forEach { append(reindentJson(it.asToolJson())).append("\n\n") }
            append(firstUser.text.trim()).append("<|eot_id|>")
        }

        remaining.forEach { message ->
            if (message.role == ChatRole.TOOL) {
                append("<|start_header_id|>ipython<|end_header_id|>\n\n")
                // A plain-text result is JSON-quoted: the template serialises anything
                // iterable, and a string is iterable as far as Jinja is concerned.
                append(message.text.jsonQuoted()).append("<|eot_id|>")
            } else {
                val keepRaw = verbatimHistory && message.role == ChatRole.ASSISTANT
                append("<|start_header_id|>").append(message.role.wireName)
                append("<|end_header_id|>\n\n")
                append(if (keepRaw) message.text else message.text.trim())
                append("<|eot_id|>")
            }
        }

        if (addGenerationPrompt) append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    private const val TOOLS_GUIDANCE =
        "Given the following functions, please respond with a JSON for a function call " +
            "with its proper arguments that best answers the given prompt.\n\n" +
            """Respond in the format {"name": function name, "parameters": dictionary of """ +
            "argument name and its value}.Do not use variables.\n\n"
}

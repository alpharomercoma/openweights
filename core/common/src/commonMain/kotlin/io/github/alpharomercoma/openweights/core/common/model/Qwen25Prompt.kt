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
 * Renders a conversation the way Qwen2.5's own chat template does.
 *
 * A transcription of `Qwen/Qwen2.5-1.5B-Instruct`'s `chat_template`, proved byte-for-byte
 * by `Qwen25PromptTest`. This is Qwen3's format with the thinking machinery absent and a
 * default system prompt present: ChatML turns, Hermes-style tools in the system block,
 * tool results collapsed into shared user turns. Hammer 2.1 is a Qwen2.5 fine-tune and
 * reads the same format.
 *
 * History is emitted verbatim — the template itself never rewrites an assistant turn, so
 * unlike Qwen3 there is no divergence to opt into: what the cache holds is what renders.
 */
object Qwen25Prompt {

    fun render(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        addGenerationPrompt: Boolean = true,
    ): String = buildString {
        appendSystem(messages, tools)

        messages.forEachIndexed { index, message ->
            when {
                message.role == ChatRole.USER ||
                    (message.role == ChatRole.SYSTEM && index > 0) ||
                    message.role == ChatRole.ASSISTANT ->
                    appendBlock(message.role.wireName, message.text)

                message.role == ChatRole.TOOL -> {
                    // A run of results is one user turn holding several responses, which is
                    // what the template does and why this cannot be written per message.
                    if (index == 0 || messages[index - 1].role != ChatRole.TOOL) {
                        append("<|im_start|>user")
                    }
                    append("\n<tool_response>\n").append(message.text).append("\n</tool_response>")
                    if (index == messages.lastIndex || messages[index + 1].role != ChatRole.TOOL) {
                        append("<|im_end|>\n")
                    }
                }

                else -> Unit // A system message at index 0 was hoisted above.
            }
        }

        if (addGenerationPrompt) append("<|im_start|>assistant\n")
    }

    /**
     * The system turn, which always exists: unlike Qwen3, Qwen2.5 falls back to its own
     * default identity when the conversation offers no system message.
     */
    private fun StringBuilder.appendSystem(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ) {
        val leading = messages.firstOrNull()?.takeIf { it.role == ChatRole.SYSTEM }?.text
        val system = leading ?: DEFAULT_SYSTEM
        if (tools.isEmpty()) {
            appendBlock("system", system)
            return
        }

        append("<|im_start|>system\n").append(system).append("\n\n")
        append(TOOLS_PREAMBLE)
        tools.forEach { append('\n').append(it.asToolJson()) }
        append(TOOLS_EPILOGUE)
        append("<|im_end|>\n")
    }

    private fun StringBuilder.appendBlock(role: String, content: String) {
        append("<|im_start|>").append(role).append('\n').append(content).append("<|im_end|>\n")
    }

    private const val DEFAULT_SYSTEM =
        "You are Qwen, created by Alibaba Cloud. You are a helpful assistant."

    private const val TOOLS_PREAMBLE =
        "# Tools\n\nYou may call one or more functions to assist with the user query.\n\n" +
            "You are provided with function signatures within <tools></tools> XML tags:\n<tools>"

    private const val TOOLS_EPILOGUE =
        "\n</tools>\n\nFor each function call, return a json object with function name and " +
            "arguments within <tool_call></tool_call> XML tags:\n<tool_call>\n" +
            """{"name": <function-name>, "arguments": <args-json-object>}""" +
            "\n</tool_call>"
}

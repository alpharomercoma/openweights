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
 * Renders a conversation the way Qwen3's own chat template does.
 *
 * llama.cpp carries a Jinja engine and every model's template inside the GGUF, so nothing
 * in this app has ever had to know a prompt format. ExecuTorch carries neither: a `.pte`
 * is a compiled graph and a tokenizer, and the text handed to it is whatever we build. So
 * for every model that runs on that engine, its template has to exist here.
 *
 * This is a transcription of `Qwen/Qwen3-1.7B`'s `chat_template`, not an approximation of
 * it. Getting a template subtly wrong does not fail loudly — the model answers, slightly
 * worse, and tool calls quietly stop being recognised — so
 * `tools/executorch/render_reference.py` renders the real Jinja template over the same
 * fixtures and the tests assert byte equality against its output.
 *
 * @see ToolCallParser for the other half of this, which reads back what the model emits.
 */
object Qwen3Prompt {

    /**
     * The conversation as one string, ready to tokenize.
     *
     * @param thinking whether to let the model reason before answering. Qwen3 disables it
     * by closing an empty `<think>` block in the assistant opener rather than by any flag,
     * so the switch is part of the prompt and costs four tokens.
     * @param verbatimHistory emit each assistant turn exactly as stored, instead of applying
     * the template's rules to it. A deliberate divergence from upstream, for a runtime whose
     * KV cache holds what was actually generated: reformatting an earlier turn — dropping
     * its reasoning, re-wrapping its tags — describes a conversation the cache is not
     * holding, and the cache then has to be thrown away and the whole prompt re-read. Off
     * by default, so `Qwen3PromptTest` still compares against upstream's own output.
     */
    fun render(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        thinking: Boolean = true,
        addGenerationPrompt: Boolean = true,
        verbatimHistory: Boolean = false,
    ): String = buildString {
        appendSystem(messages, tools)

        val lastQuery = lastQueryIndex(messages)
        messages.forEachIndexed { index, message ->
            when {
                message.role == ChatRole.USER || (message.role == ChatRole.SYSTEM && index > 0) ->
                    appendBlock(message.role.wireName, message.text)

                message.role == ChatRole.ASSISTANT ->
                    if (verbatimHistory) {
                        appendBlock("assistant", message.text)
                    } else {
                        appendAssistant(message, index, lastQuery, index == messages.lastIndex)
                    }

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

        if (addGenerationPrompt) {
            append("<|im_start|>assistant\n")
            if (!thinking) append("<think>\n\n</think>\n\n")
        }
    }

    /**
     * The system turn, which is also where tools live.
     *
     * Qwen3 has no default system prompt: with neither a system message nor tools, the
     * prompt opens straight into the first user turn. Emitting an empty system block
     * instead would be a difference the model can see.
     *
     * Only a system message in *first* position is hoisted, which is the template's own
     * rule (`messages[0].role == 'system'`). One that arrives later is an ordinary turn and
     * is rendered in place by the loop above.
     */
    private fun StringBuilder.appendSystem(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ) {
        val leading = messages.firstOrNull()?.takeIf { it.role == ChatRole.SYSTEM }?.text.orEmpty()
        if (tools.isEmpty()) {
            if (leading.isNotEmpty()) appendBlock("system", leading)
            return
        }

        append("<|im_start|>system\n")
        if (leading.isNotEmpty()) append(leading).append("\n\n")
        append(TOOLS_PREAMBLE)
        tools.forEach { append('\n').append(it.asJson()) }
        append(TOOLS_EPILOGUE)
        append("<|im_end|>\n")
    }

    /**
     * An assistant turn, which is the only place reasoning survives into the prompt.
     *
     * Qwen3 keeps thinking for turns that come *after* the user's last real question and
     * drops it from everything earlier. That is not a tidy-up: within one agentic turn the
     * model is reasoning across its own tool calls, and cutting that out mid-run takes away
     * the working-out it is about to build on. Once the user asks something new, the whole
     * run becomes history and the reasoning goes.
     *
     * So the rule is per turn rather than global, and [lastQuery] is what separates the two.
     */
    private fun StringBuilder.appendAssistant(
        message: ChatMessage,
        index: Int,
        lastQuery: Int,
        isLast: Boolean,
    ) {
        val raw = message.text
        // Reasoning ends at the first close tag, the answer begins after the last one, and
        // those are deliberately not the same tag. That is the template's own arithmetic,
        // and it only diverges on a reply carrying two blocks — which is exactly the
        // malformed case where guessing differently would put thinking in the answer.
        val firstClose = raw.indexOf(THINK_CLOSE)
        val lastClose = raw.lastIndexOf(THINK_CLOSE)
        val reasoning = if (firstClose < 0) {
            ""
        } else {
            raw.take(firstClose).trimEnd('\n').substringAfterLast(THINK_OPEN).trimStart('\n')
        }
        val content = if (lastClose < 0) {
            raw
        } else {
            raw.substring(lastClose + THINK_CLOSE.length).trimStart('\n')
        }

        append("<|im_start|>assistant\n")
        if (index > lastQuery && (isLast || reasoning.isNotEmpty())) {
            append("<think>\n").append(reasoning.trim('\n')).append("\n</think>\n\n")
        }
        append(content).append("<|im_end|>\n")
    }

    /**
     * Where the user last actually asked something.
     *
     * A tool result is delivered in a user turn, so "the last user message" is the wrong
     * question — in an agentic run the most recent one is usually a `<tool_response>`. The
     * template recognises those by their wrapper and looks past them, and so does this.
     */
    private fun lastQueryIndex(messages: List<ChatMessage>): Int {
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (message.role != ChatRole.USER) continue
            val text = message.text
            val wrapped = text.startsWith(RESPONSE_OPEN) && text.endsWith(RESPONSE_CLOSE)
            if (!wrapped) return index
        }
        return messages.size - 1
    }

    private fun StringBuilder.appendBlock(role: String, content: String) {
        append("<|im_start|>").append(role).append('\n').append(content).append("<|im_end|>\n")
    }

    /**
     * A tool as the OpenAI-shaped object Qwen3 was trained to read.
     *
     * [ToolDefinition.parametersJson] is already a JSON Schema object and is spliced in
     * verbatim: re-encoding it would reorder keys, and the schema is the model's only
     * description of what the arguments mean.
     */
    private fun ToolDefinition.asJson(): String =
        """{"type": "function", "function": {"name": ${name.quoted()}, """ +
            """"description": ${description.quoted()}, "parameters": $parametersJson}}"""

    private fun String.quoted(): String = buildString {
        append('"')
        this@quoted.forEach { character ->
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character < ' ' -> append("\\u")
                    .append(character.code.toString(HEX).padStart(ESCAPE_DIGITS, '0'))
                else -> append(character)
            }
        }
        append('"')
    }

    private const val HEX = 16
    private const val ESCAPE_DIGITS = 4

    private const val RESPONSE_OPEN = "<tool_response>"
    private const val RESPONSE_CLOSE = "</tool_response>"

    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"

    private const val TOOLS_PREAMBLE =
        "# Tools\n\nYou may call one or more functions to assist with the user query.\n\n" +
            "You are provided with function signatures within <tools></tools> XML tags:\n<tools>"

    private const val TOOLS_EPILOGUE =
        "\n</tools>\n\nFor each function call, return a json object with function name and " +
            "arguments within <tool_call></tool_call> XML tags:\n<tool_call>\n" +
            """{"name": <function-name>, "arguments": <args-json-object>}""" +
            "\n</tool_call>"
}

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
 * Renders a conversation the way SmolLM3's own chat template does.
 *
 * A transcription of `HuggingFaceTB/SmolLM3-3B`'s `chat_template.jinja`, proved
 * byte-for-byte by `SmolLm3PromptTest`. A system block always opens the prompt with
 * metadata — knowledge cutoff, today's date, the reasoning mode — followed by custom or
 * default instructions and any tools. Thinking is a mode, not a block: `/no_think` closes
 * an empty `<think>` ahead of every assistant turn, and a system message can flip the
 * mode by containing either marker. `/system_override` replaces the whole scaffold.
 *
 * Two upstream behaviours are reproduced deliberately rather than fixed: the system block
 * is only closed with `<|im_end|>` when tools are present, and a system message that is
 * not first is dropped entirely. Both are what the template ships and what every
 * transformers user renders, so they are what the model has been fine-tuned and served on.
 *
 * @param date today, in SmolLM3's own spelling — a parameter so the tests can pin it.
 */
object SmolLm3Prompt {

    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun render(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        thinking: Boolean = true,
        addGenerationPrompt: Boolean = true,
        date: String = promptDateToday().asSmolLm3Date(),
    ): String = buildString {
        val system = messages.firstOrNull()?.takeIf { it.role == ChatRole.SYSTEM }?.text
        // The system message outranks the parameter, and /no_think outranks /think —
        // the template's own precedence.
        val mode = when {
            system != null && NO_THINK in system -> NO_THINK
            system != null && THINK in system -> THINK
            thinking -> THINK
            else -> NO_THINK
        }
        val custom = system?.replace(NO_THINK, "")?.replace(THINK, "")?.trimEnd()

        append("<|im_start|>system\n")
        if (system != null && OVERRIDE in system) {
            append(custom.orEmpty().replace(OVERRIDE, "").trimEnd())
            append("<|im_end|>\n")
        } else {
            append("## Metadata\n\n")
            append("Knowledge Cutoff Date: June 2025\n")
            append("Today Date: ").append(date).append('\n')
            append("Reasoning Mode: ").append(mode).append("\n\n")
            append("## Custom Instructions\n\n")
            when {
                !custom.isNullOrEmpty() -> append(custom).append("\n\n")
                mode == THINK -> append(DEFAULT_THINK).append("\n\n")
                else -> append(DEFAULT_NO_THINK).append("\n\n")
            }
            if (tools.isNotEmpty()) {
                append(TOOLS_PREAMBLE)
                tools.forEach { append(it.asToolJson()).append('\n') }
                append(TOOLS_EPILOGUE)
                append("\n\n")
                // The one path on which the system block is closed at all.
                append("<|im_end|>\n")
            }
        }

        messages.forEach { message ->
            when (message.role) {
                // A tool result renders as a user turn; a stray system message renders
                // as nothing, because the template has no branch for it.
                ChatRole.USER, ChatRole.TOOL ->
                    append("<|im_start|>user\n").append(message.text).append("<|im_end|>\n")

                ChatRole.ASSISTANT -> {
                    append("<|im_start|>assistant\n")
                    if (mode == NO_THINK) append("<think>\n\n</think>\n")
                    append(message.text.trimStart('\n')).append("<|im_end|>\n")
                }

                ChatRole.SYSTEM -> Unit
            }
        }

        if (addGenerationPrompt) {
            append("<|im_start|>assistant\n")
            if (mode == NO_THINK) append("<think>\n\n</think>\n")
        }
    }

    private const val THINK = "/think"
    private const val NO_THINK = "/no_think"
    private const val OVERRIDE = "/system_override"

    private const val DEFAULT_NO_THINK =
        "You are a helpful AI assistant named SmolLM, trained by Hugging Face."

    private const val DEFAULT_THINK =
        "You are a helpful AI assistant named SmolLM, trained by Hugging Face. Your role as " +
            "an assistant involves thoroughly exploring questions through a systematic " +
            "thinking process before providing the final precise and accurate solutions. " +
            "This requires engaging in a comprehensive cycle of analysis, summarizing, " +
            "exploration, reassessment, reflection, backtracking, and iteration to develop " +
            "well-considered thinking process. Please structure your response into two main " +
            "sections: Thought and Solution using the specified format: <think> Thought " +
            "section </think> Solution section. In the Thought section, detail your " +
            "reasoning process in steps. Each step should include detailed considerations " +
            "such as analysing questions, summarizing relevant findings, brainstorming new " +
            "ideas, verifying the accuracy of the current steps, refining any errors, and " +
            "revisiting previous steps. In the Solution section, based on various attempts, " +
            "explorations, and reflections from the Thought section, systematically present " +
            "the final solution that you deem correct. The Solution section should be " +
            "logical, accurate, and concise and detail necessary steps needed to reach the " +
            "conclusion."

    private const val TOOLS_PREAMBLE =
        "### Tools\n\nYou may call one or more functions to assist with the user query.\n" +
            "You are provided with function signatures within <tools></tools> XML tags:\n\n" +
            "<tools>\n"

    private const val TOOLS_EPILOGUE =
        "</tools>\n\nFor each function call, return a json object with function name and " +
            "arguments within <tool_call></tool_call> XML tags:\n<tool_call>\n" +
            """{"name": <function-name>, "arguments": <args-json-object>}""" +
            "\n</tool_call>"
}

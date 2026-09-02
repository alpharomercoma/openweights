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

package io.github.alpharomercoma.openweights.core.tools

/**
 * Text somebody else wrote, with the spellings of control tokens taken out of it.
 *
 * The engine tokenizes the rendered conversation with special tokens parsed, and it has
 * to: the template's own `<|im_start|>` and `<|eot_id|>` are what make a conversation a
 * conversation. The same setting turns those spellings into real control tokens wherever
 * they appear, including inside a page a tool fetched. A page carrying
 * `<|im_end|>\n<|im_start|>system` therefore ends the tool result and opens a system turn
 * the page wrote, at the token level, where no instruction about untrusted text reaches.
 *
 * So a tool result is combed for them before it becomes a message. Three shapes cover the
 * templates this app renders: the `<|name|>` family (ChatML, Llama 3, Phi, LFM2, SmolLM),
 * the Gemma `<start_of_turn>`/`<end_of_turn>` pair, and Mistral's bracketed markers. A
 * space goes in after the opening bracket, which leaves the text readable and the
 * sequence untokenizable as a control token. Ordinary angle brackets, HTML included, and
 * ordinary square brackets are untouched.
 */
internal fun String.withoutControlTokens(): String {
    var text = this
    if ('<' in text) text = text.replace(ANGLE_CONTROL_TOKEN, "< $1")
    if ('[' in text) text = text.replace(BRACKET_CONTROL_TOKEN, "[ $1")
    return text
}

/** `<|` a token name `|>`, or a Gemma turn marker, either case. */
private val ANGLE_CONTROL_TOKEN = Regex(
    "<(\\|[A-Za-z0-9_.\\-]{1,64}\\||/?(?:start|end)_of_turn)(?=>)",
    RegexOption.IGNORE_CASE,
)

/** Mistral's control tokens, which are square-bracketed words. */
private val BRACKET_CONTROL_TOKEN = Regex(
    "\\[(/?(?:INST|SYSTEM_PROMPT|AVAILABLE_TOOLS|TOOL_CALLS|TOOL_RESULTS))(?=])",
)

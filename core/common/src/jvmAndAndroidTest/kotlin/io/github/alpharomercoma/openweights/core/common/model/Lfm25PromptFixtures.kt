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
 * Prompts rendered by the `chat_template` that LiquidAI/LFM2.5-1.2B-Instruct ships with.
 *
 * Generated, not written. `tools/executorch/render_reference.py` fetches the template from
 * the Hub and renders it with Jinja over the same conversations `Lfm25PromptTest` builds,
 * so these strings are what upstream produces rather than anybody's idea of what it should
 * produce. Do not edit by hand: rerun the script and read the diff.
 */
internal object Lfm25PromptFixtures {
    const val PLAIN: String =
        "<|startoftext|><|im_start|>user\n" +
            "What is the capital of Japan?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val WITH_SYSTEM: String =
        "<|startoftext|><|im_start|>system\n" +
            "You are a terse assistant.<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the capital of Japan?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val WITH_TOOLS: String =
        "<|startoftext|><|im_start|>system\n" +
            "List of tools: [{\"type\": \"function\", \"function\": {\"name\": \"web_search\"" +
            ", \"description\": \"Search the web for current information.\", \"parameters\": " +
            "{\"type\": \"object\", \"properties\": {\"query\": {\"type\": \"string\", \"desc" +
            "ription\": \"What to search for\"}}, \"required\": [\"query\"]}}}]<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the weather in Manila?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val PRIOR_THINKING_DROPPED: String =
        "<|startoftext|><|im_start|>user\n" +
            "What is 2+2?<|im_end|>\n" +
            "<|im_start|>assistant\n" +
            "Four.<|im_end|>\n" +
            "<|im_start|>user\n" +
            "And 3+3?<|im_end|>\n" +
            "<|im_start|>assistant\n" +
            "<think>Also simple.</think>Six.<|im_end|>\n"

    const val TOOL_RUN: String =
        "<|startoftext|><|im_start|>system\n" +
            "List of tools: [{\"type\": \"function\", \"function\": {\"name\": \"web_search\"" +
            ", \"description\": \"Search the web for current information.\", \"parameters\": " +
            "{\"type\": \"object\", \"properties\": {\"query\": {\"type\": \"string\", \"desc" +
            "ription\": \"What to search for\"}}, \"required\": [\"query\"]}}}]<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the weather in Manila?<|im_end|>\n" +
            "<|im_start|>assistant\n" +
            "<|tool_call_start|>[web_search(query='Manila weather')]<|tool_call_end|><|im_end" +
            "|>\n" +
            "<|im_start|>tool\n" +
            "Manila: 31C, humid.<|im_end|>\n" +
            "<|im_start|>assistant\n"
}

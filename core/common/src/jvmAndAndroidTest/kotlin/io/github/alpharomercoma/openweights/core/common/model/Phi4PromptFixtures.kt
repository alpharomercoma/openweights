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
 * Prompts rendered by the `chat_template` that microsoft/Phi-4-mini-instruct ships with.
 *
 * Generated, not written. `tools/executorch/render_reference.py` fetches the template from
 * the Hub and renders it with Jinja over the same conversations `Phi4PromptTest` builds,
 * so these strings are what upstream produces rather than anybody's idea of what it should
 * produce. Do not edit by hand: rerun the script and read the diff.
 */
internal object Phi4PromptFixtures {
    const val PLAIN: String =
        "<|user|>What is the capital of Japan?<|end|><|assistant|>"

    const val WITH_SYSTEM: String =
        "<|system|>You are a terse assistant.<|end|><|user|>What is the capital of Japan?" +
            "<|end|><|assistant|>"

    const val WITH_TOOLS: String =
        "<|system|>You are a terse assistant.<|tool|>[{\"type\": \"function\", \"function" +
            "\": {\"name\": \"web_search\", \"description\": \"Search the web for current inf" +
            "ormation.\", \"parameters\": {\"type\": \"object\", \"properties\": {\"query\": " +
            "{\"type\": \"string\", \"description\": \"What to search for\"}}, \"required\": " +
            "[\"query\"]}}}]<|/tool|><|end|><|user|>What is the weather in Manila?<|end|><|as" +
            "sistant|>"

    const val WITH_TOOLS_NO_SYSTEM: String =
        "<|system|><|tool|>[{\"type\": \"function\", \"function\": {\"name\": \"web_searc" +
            "h\", \"description\": \"Search the web for current information.\", \"parameters" +
            "\": {\"type\": \"object\", \"properties\": {\"query\": {\"type\": \"string\", \"" +
            "description\": \"What to search for\"}}, \"required\": [\"query\"]}}}]<|/tool|><" +
            "|end|><|user|>What is the weather in Manila?<|end|><|assistant|>"

    const val MULTI_TURN: String =
        "<|user|>What is 2+2?<|end|><|assistant|>Four.<|end|><|user|>And 3+3?<|end|><|ass" +
            "istant|>"
}

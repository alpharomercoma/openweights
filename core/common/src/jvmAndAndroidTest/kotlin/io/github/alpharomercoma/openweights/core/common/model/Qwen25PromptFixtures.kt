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
 * Prompts rendered by the `chat_template` that Qwen/Qwen2.5-1.5B-Instruct ships with.
 *
 * Generated, not written. `tools/executorch/render_reference.py` fetches the template from
 * the Hub and renders it with Jinja over the same conversations `Qwen25PromptTest` builds,
 * so these strings are what upstream produces rather than anybody's idea of what it should
 * produce. Do not edit by hand: rerun the script and read the diff.
 */
internal object Qwen25PromptFixtures {
    const val PLAIN: String =
        "<|im_start|>system\n" +
            "You are Qwen, created by Alibaba Cloud. You are a helpful assistant.<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the capital of Japan?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val WITH_SYSTEM: String =
        "<|im_start|>system\n" +
            "You are a terse assistant.<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the capital of Japan?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val WITH_TOOLS: String =
        "<|im_start|>system\n" +
            "You are Qwen, created by Alibaba Cloud. You are a helpful assistant.\n" +
            "\n" +
            "# Tools\n" +
            "\n" +
            "You may call one or more functions to assist with the user query.\n" +
            "\n" +
            "You are provided with function signatures within <tools></tools> XML tags:\n" +
            "<tools>\n" +
            "{\"type\": \"function\", \"function\": {\"name\": \"web_search\", \"description" +
            "\": \"Search the web for current information.\", \"parameters\": {\"type\": \"ob" +
            "ject\", \"properties\": {\"query\": {\"type\": \"string\", \"description\": \"Wh" +
            "at to search for\"}}, \"required\": [\"query\"]}}}\n" +
            "</tools>\n" +
            "\n" +
            "For each function call, return a json object with function name and arguments wi" +
            "thin <tool_call></tool_call> XML tags:\n" +
            "<tool_call>\n" +
            "{\"name\": <function-name>, \"arguments\": <args-json-object>}\n" +
            "</tool_call><|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the weather in Manila?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val WITH_SYSTEM_AND_TOOLS: String =
        "<|im_start|>system\n" +
            "You are a terse assistant.\n" +
            "\n" +
            "# Tools\n" +
            "\n" +
            "You may call one or more functions to assist with the user query.\n" +
            "\n" +
            "You are provided with function signatures within <tools></tools> XML tags:\n" +
            "<tools>\n" +
            "{\"type\": \"function\", \"function\": {\"name\": \"web_search\", \"description" +
            "\": \"Search the web for current information.\", \"parameters\": {\"type\": \"ob" +
            "ject\", \"properties\": {\"query\": {\"type\": \"string\", \"description\": \"Wh" +
            "at to search for\"}}, \"required\": [\"query\"]}}}\n" +
            "</tools>\n" +
            "\n" +
            "For each function call, return a json object with function name and arguments wi" +
            "thin <tool_call></tool_call> XML tags:\n" +
            "<tool_call>\n" +
            "{\"name\": <function-name>, \"arguments\": <args-json-object>}\n" +
            "</tool_call><|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the weather in Manila?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val TOOL_RUN: String =
        "<|im_start|>system\n" +
            "You are Qwen, created by Alibaba Cloud. You are a helpful assistant.\n" +
            "\n" +
            "# Tools\n" +
            "\n" +
            "You may call one or more functions to assist with the user query.\n" +
            "\n" +
            "You are provided with function signatures within <tools></tools> XML tags:\n" +
            "<tools>\n" +
            "{\"type\": \"function\", \"function\": {\"name\": \"web_search\", \"description" +
            "\": \"Search the web for current information.\", \"parameters\": {\"type\": \"ob" +
            "ject\", \"properties\": {\"query\": {\"type\": \"string\", \"description\": \"Wh" +
            "at to search for\"}}, \"required\": [\"query\"]}}}\n" +
            "</tools>\n" +
            "\n" +
            "For each function call, return a json object with function name and arguments wi" +
            "thin <tool_call></tool_call> XML tags:\n" +
            "<tool_call>\n" +
            "{\"name\": <function-name>, \"arguments\": <args-json-object>}\n" +
            "</tool_call><|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the weather in Manila?<|im_end|>\n" +
            "<|im_start|>assistant\n" +
            "<tool_call>\n" +
            "{\"name\": \"web_search\", \"arguments\": {\"query\": \"Manila weather\"}}\n" +
            "</tool_call><|im_end|>\n" +
            "<|im_start|>user\n" +
            "<tool_response>\n" +
            "Manila: 31C, humid.\n" +
            "</tool_response><|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val TWO_TOOL_RESULTS: String =
        "<|im_start|>system\n" +
            "You are Qwen, created by Alibaba Cloud. You are a helpful assistant.\n" +
            "\n" +
            "# Tools\n" +
            "\n" +
            "You may call one or more functions to assist with the user query.\n" +
            "\n" +
            "You are provided with function signatures within <tools></tools> XML tags:\n" +
            "<tools>\n" +
            "{\"type\": \"function\", \"function\": {\"name\": \"web_search\", \"description" +
            "\": \"Search the web for current information.\", \"parameters\": {\"type\": \"ob" +
            "ject\", \"properties\": {\"query\": {\"type\": \"string\", \"description\": \"Wh" +
            "at to search for\"}}, \"required\": [\"query\"]}}}\n" +
            "</tools>\n" +
            "\n" +
            "For each function call, return a json object with function name and arguments wi" +
            "thin <tool_call></tool_call> XML tags:\n" +
            "<tool_call>\n" +
            "{\"name\": <function-name>, \"arguments\": <args-json-object>}\n" +
            "</tool_call><|im_end|>\n" +
            "<|im_start|>user\n" +
            "Compare Manila and Tokyo.<|im_end|>\n" +
            "<|im_start|>assistant\n" +
            "Looking both up.<|im_end|>\n" +
            "<|im_start|>user\n" +
            "<tool_response>\n" +
            "Manila: 31C.\n" +
            "</tool_response>\n" +
            "<tool_response>\n" +
            "Tokyo: 22C.\n" +
            "</tool_response><|im_end|>\n" +
            "<|im_start|>assistant\n"
}

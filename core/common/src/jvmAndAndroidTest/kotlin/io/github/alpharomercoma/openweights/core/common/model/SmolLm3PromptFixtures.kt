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
 * Prompts rendered by the `chat_template` that HuggingFaceTB/SmolLM3-3B ships with.
 *
 * Generated, not written. `tools/executorch/render_reference.py` fetches the template from
 * the Hub and renders it with Jinja over the same conversations `SmolLm3PromptTest` builds,
 * so these strings are what upstream produces rather than anybody's idea of what it should
 * produce. Do not edit by hand: rerun the script and read the diff.
 */
internal object SmolLm3PromptFixtures {
    const val PLAIN: String =
        "<|im_start|>system\n" +
            "## Metadata\n" +
            "\n" +
            "Knowledge Cutoff Date: June 2025\n" +
            "Today Date: 29 August 2026\n" +
            "Reasoning Mode: /think\n" +
            "\n" +
            "## Custom Instructions\n" +
            "\n" +
            "You are a helpful AI assistant named SmolLM, trained by Hugging Face. Your role " +
            "as an assistant involves thoroughly exploring questions through a systematic thi" +
            "nking process before providing the final precise and accurate solutions. This re" +
            "quires engaging in a comprehensive cycle of analysis, summarizing, exploration, " +
            "reassessment, reflection, backtracking, and iteration to develop well-considered" +
            " thinking process. Please structure your response into two main sections: Though" +
            "t and Solution using the specified format: <think> Thought section </think> Solu" +
            "tion section. In the Thought section, detail your reasoning process in steps. Ea" +
            "ch step should include detailed considerations such as analysing questions, summ" +
            "arizing relevant findings, brainstorming new ideas, verifying the accuracy of th" +
            "e current steps, refining any errors, and revisiting previous steps. In the Solu" +
            "tion section, based on various attempts, explorations, and reflections from the " +
            "Thought section, systematically present the final solution that you deem correct" +
            ". The Solution section should be logical, accurate, and concise and detail neces" +
            "sary steps needed to reach the conclusion.\n" +
            "\n" +
            "<|im_start|>user\n" +
            "What is the capital of Japan?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val PLAIN_NO_THINK: String =
        "<|im_start|>system\n" +
            "## Metadata\n" +
            "\n" +
            "Knowledge Cutoff Date: June 2025\n" +
            "Today Date: 29 August 2026\n" +
            "Reasoning Mode: /no_think\n" +
            "\n" +
            "## Custom Instructions\n" +
            "\n" +
            "You are a helpful AI assistant named SmolLM, trained by Hugging Face.\n" +
            "\n" +
            "<|im_start|>user\n" +
            "What is the capital of Japan?<|im_end|>\n" +
            "<|im_start|>assistant\n" +
            "<think>\n" +
            "\n" +
            "</think>\n"

    const val WITH_SYSTEM: String =
        "<|im_start|>system\n" +
            "## Metadata\n" +
            "\n" +
            "Knowledge Cutoff Date: June 2025\n" +
            "Today Date: 29 August 2026\n" +
            "Reasoning Mode: /think\n" +
            "\n" +
            "## Custom Instructions\n" +
            "\n" +
            "You are a terse assistant.\n" +
            "\n" +
            "<|im_start|>user\n" +
            "What is the capital of Japan?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val SYSTEM_OVERRIDE: String =
        "<|im_start|>system\n" +
            "You are a terse assistant.<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the capital of Japan?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val WITH_TOOLS: String =
        "<|im_start|>system\n" +
            "## Metadata\n" +
            "\n" +
            "Knowledge Cutoff Date: June 2025\n" +
            "Today Date: 29 August 2026\n" +
            "Reasoning Mode: /think\n" +
            "\n" +
            "## Custom Instructions\n" +
            "\n" +
            "You are a helpful AI assistant named SmolLM, trained by Hugging Face. Your role " +
            "as an assistant involves thoroughly exploring questions through a systematic thi" +
            "nking process before providing the final precise and accurate solutions. This re" +
            "quires engaging in a comprehensive cycle of analysis, summarizing, exploration, " +
            "reassessment, reflection, backtracking, and iteration to develop well-considered" +
            " thinking process. Please structure your response into two main sections: Though" +
            "t and Solution using the specified format: <think> Thought section </think> Solu" +
            "tion section. In the Thought section, detail your reasoning process in steps. Ea" +
            "ch step should include detailed considerations such as analysing questions, summ" +
            "arizing relevant findings, brainstorming new ideas, verifying the accuracy of th" +
            "e current steps, refining any errors, and revisiting previous steps. In the Solu" +
            "tion section, based on various attempts, explorations, and reflections from the " +
            "Thought section, systematically present the final solution that you deem correct" +
            ". The Solution section should be logical, accurate, and concise and detail neces" +
            "sary steps needed to reach the conclusion.\n" +
            "\n" +
            "### Tools\n" +
            "\n" +
            "You may call one or more functions to assist with the user query.\n" +
            "You are provided with function signatures within <tools></tools> XML tags:\n" +
            "\n" +
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
            "</tool_call>\n" +
            "\n" +
            "<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the weather in Manila?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val MULTI_TURN: String =
        "<|im_start|>system\n" +
            "## Metadata\n" +
            "\n" +
            "Knowledge Cutoff Date: June 2025\n" +
            "Today Date: 29 August 2026\n" +
            "Reasoning Mode: /think\n" +
            "\n" +
            "## Custom Instructions\n" +
            "\n" +
            "You are a helpful AI assistant named SmolLM, trained by Hugging Face. Your role " +
            "as an assistant involves thoroughly exploring questions through a systematic thi" +
            "nking process before providing the final precise and accurate solutions. This re" +
            "quires engaging in a comprehensive cycle of analysis, summarizing, exploration, " +
            "reassessment, reflection, backtracking, and iteration to develop well-considered" +
            " thinking process. Please structure your response into two main sections: Though" +
            "t and Solution using the specified format: <think> Thought section </think> Solu" +
            "tion section. In the Thought section, detail your reasoning process in steps. Ea" +
            "ch step should include detailed considerations such as analysing questions, summ" +
            "arizing relevant findings, brainstorming new ideas, verifying the accuracy of th" +
            "e current steps, refining any errors, and revisiting previous steps. In the Solu" +
            "tion section, based on various attempts, explorations, and reflections from the " +
            "Thought section, systematically present the final solution that you deem correct" +
            ". The Solution section should be logical, accurate, and concise and detail neces" +
            "sary steps needed to reach the conclusion.\n" +
            "\n" +
            "<|im_start|>user\n" +
            "What is 2+2?<|im_end|>\n" +
            "<|im_start|>assistant\n" +
            "Four.<|im_end|>\n" +
            "<|im_start|>user\n" +
            "And 3+3?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val TOOL_RUN: String =
        "<|im_start|>system\n" +
            "## Metadata\n" +
            "\n" +
            "Knowledge Cutoff Date: June 2025\n" +
            "Today Date: 29 August 2026\n" +
            "Reasoning Mode: /think\n" +
            "\n" +
            "## Custom Instructions\n" +
            "\n" +
            "You are a helpful AI assistant named SmolLM, trained by Hugging Face. Your role " +
            "as an assistant involves thoroughly exploring questions through a systematic thi" +
            "nking process before providing the final precise and accurate solutions. This re" +
            "quires engaging in a comprehensive cycle of analysis, summarizing, exploration, " +
            "reassessment, reflection, backtracking, and iteration to develop well-considered" +
            " thinking process. Please structure your response into two main sections: Though" +
            "t and Solution using the specified format: <think> Thought section </think> Solu" +
            "tion section. In the Thought section, detail your reasoning process in steps. Ea" +
            "ch step should include detailed considerations such as analysing questions, summ" +
            "arizing relevant findings, brainstorming new ideas, verifying the accuracy of th" +
            "e current steps, refining any errors, and revisiting previous steps. In the Solu" +
            "tion section, based on various attempts, explorations, and reflections from the " +
            "Thought section, systematically present the final solution that you deem correct" +
            ". The Solution section should be logical, accurate, and concise and detail neces" +
            "sary steps needed to reach the conclusion.\n" +
            "\n" +
            "### Tools\n" +
            "\n" +
            "You may call one or more functions to assist with the user query.\n" +
            "You are provided with function signatures within <tools></tools> XML tags:\n" +
            "\n" +
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
            "</tool_call>\n" +
            "\n" +
            "<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the weather in Manila?<|im_end|>\n" +
            "<|im_start|>assistant\n" +
            "<tool_call>\n" +
            "{\"name\": \"web_search\", \"arguments\": {\"query\": \"Manila weather\"}}\n" +
            "</tool_call><|im_end|>\n" +
            "<|im_start|>user\n" +
            "Manila: 31C, humid.<|im_end|>\n" +
            "<|im_start|>assistant\n"
}

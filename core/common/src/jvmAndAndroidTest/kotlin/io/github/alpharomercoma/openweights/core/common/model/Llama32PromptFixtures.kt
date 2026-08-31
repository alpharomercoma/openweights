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
 * Prompts rendered by the `chat_template` that meta-llama/Llama-3.2-1B-Instruct ships with.
 *
 * Generated, not written. `tools/executorch/render_reference.py` fetches the template from
 * the Hub and renders it with Jinja over the same conversations `Llama32PromptTest` builds,
 * so these strings are what upstream produces rather than anybody's idea of what it should
 * produce. Do not edit by hand: rerun the script and read the diff.
 */
internal object Llama32PromptFixtures {
    const val PLAIN: String =
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
            "\n" +
            "Cutting Knowledge Date: December 2023\n" +
            "Today Date: 26 Jul 2024\n" +
            "\n" +
            "<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
            "\n" +
            "What is the capital of Japan?<|eot_id|><|start_header_id|>assistant<|end_header_" +
            "id|>\n" +
            "\n"

    const val WITH_SYSTEM: String =
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
            "\n" +
            "Cutting Knowledge Date: December 2023\n" +
            "Today Date: 26 Jul 2024\n" +
            "\n" +
            "You are a terse assistant.<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
            "\n" +
            "What is the capital of Japan?<|eot_id|><|start_header_id|>assistant<|end_header_" +
            "id|>\n" +
            "\n"

    const val WITH_TOOLS: String =
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
            "\n" +
            "Environment: ipython\n" +
            "Cutting Knowledge Date: December 2023\n" +
            "Today Date: 26 Jul 2024\n" +
            "\n" +
            "<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
            "\n" +
            "Given the following functions, please respond with a JSON for a function call wi" +
            "th its proper arguments that best answers the given prompt.\n" +
            "\n" +
            "Respond in the format {\"name\": function name, \"parameters\": dictionary of ar" +
            "gument name and its value}.Do not use variables.\n" +
            "\n" +
            "{\n" +
            "    \"type\": \"function\",\n" +
            "    \"function\": {\n" +
            "        \"name\": \"web_search\",\n" +
            "        \"description\": \"Search the web for current information.\",\n" +
            "        \"parameters\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "                \"query\": {\n" +
            "                    \"type\": \"string\",\n" +
            "                    \"description\": \"What to search for\"\n" +
            "                }\n" +
            "            },\n" +
            "            \"required\": [\n" +
            "                \"query\"\n" +
            "            ]\n" +
            "        }\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "What is the weather in Manila?<|eot_id|><|start_header_id|>assistant<|end_header" +
            "_id|>\n" +
            "\n"

    const val TOOL_CALL: String =
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
            "\n" +
            "Environment: ipython\n" +
            "Cutting Knowledge Date: December 2023\n" +
            "Today Date: 26 Jul 2024\n" +
            "\n" +
            "<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
            "\n" +
            "Given the following functions, please respond with a JSON for a function call wi" +
            "th its proper arguments that best answers the given prompt.\n" +
            "\n" +
            "Respond in the format {\"name\": function name, \"parameters\": dictionary of ar" +
            "gument name and its value}.Do not use variables.\n" +
            "\n" +
            "{\n" +
            "    \"type\": \"function\",\n" +
            "    \"function\": {\n" +
            "        \"name\": \"web_search\",\n" +
            "        \"description\": \"Search the web for current information.\",\n" +
            "        \"parameters\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "                \"query\": {\n" +
            "                    \"type\": \"string\",\n" +
            "                    \"description\": \"What to search for\"\n" +
            "                }\n" +
            "            },\n" +
            "            \"required\": [\n" +
            "                \"query\"\n" +
            "            ]\n" +
            "        }\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "What is the weather in Manila?<|eot_id|><|start_header_id|>assistant<|end_header" +
            "_id|>\n" +
            "\n" +
            "{\"name\": \"web_search\", \"parameters\": {\"query\": \"Manila weather\"}}<|eot" +
            "_id|><|start_header_id|>assistant<|end_header_id|>\n" +
            "\n"

    const val TOOL_RUN: String =
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
            "\n" +
            "Environment: ipython\n" +
            "Cutting Knowledge Date: December 2023\n" +
            "Today Date: 26 Jul 2024\n" +
            "\n" +
            "<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
            "\n" +
            "Given the following functions, please respond with a JSON for a function call wi" +
            "th its proper arguments that best answers the given prompt.\n" +
            "\n" +
            "Respond in the format {\"name\": function name, \"parameters\": dictionary of ar" +
            "gument name and its value}.Do not use variables.\n" +
            "\n" +
            "{\n" +
            "    \"type\": \"function\",\n" +
            "    \"function\": {\n" +
            "        \"name\": \"web_search\",\n" +
            "        \"description\": \"Search the web for current information.\",\n" +
            "        \"parameters\": {\n" +
            "            \"type\": \"object\",\n" +
            "            \"properties\": {\n" +
            "                \"query\": {\n" +
            "                    \"type\": \"string\",\n" +
            "                    \"description\": \"What to search for\"\n" +
            "                }\n" +
            "            },\n" +
            "            \"required\": [\n" +
            "                \"query\"\n" +
            "            ]\n" +
            "        }\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "What is the weather in Manila?<|eot_id|><|start_header_id|>assistant<|end_header" +
            "_id|>\n" +
            "\n" +
            "{\"name\": \"web_search\", \"parameters\": {\"query\": \"Manila weather\"}}<|eot" +
            "_id|><|start_header_id|>ipython<|end_header_id|>\n" +
            "\n" +
            "\"Manila: 31C, humid.\"<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n" +
            "\n"

    const val MULTI_TURN: String =
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
            "\n" +
            "Cutting Knowledge Date: December 2023\n" +
            "Today Date: 26 Jul 2024\n" +
            "\n" +
            "<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
            "\n" +
            "What is 2+2?<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n" +
            "\n" +
            "Four.<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
            "\n" +
            "And 3+3?<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n" +
            "\n"
}

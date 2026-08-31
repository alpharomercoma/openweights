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
 * Prompts rendered by the `chat_template` that google/gemma-3-1b-it ships with.
 *
 * Generated, not written. `tools/executorch/render_reference.py` fetches the template from
 * the Hub and renders it with Jinja over the same conversations `Gemma3PromptTest` builds,
 * so these strings are what upstream produces rather than anybody's idea of what it should
 * produce. Do not edit by hand: rerun the script and read the diff.
 */
internal object Gemma3PromptFixtures {
    const val PLAIN: String =
        "<bos><start_of_turn>user\n" +
            "What is the capital of Japan?<end_of_turn>\n" +
            "<start_of_turn>model\n"

    const val WITH_SYSTEM: String =
        "<bos><start_of_turn>user\n" +
            "You are a terse assistant.\n" +
            "\n" +
            "What is the capital of Japan?<end_of_turn>\n" +
            "<start_of_turn>model\n"

    const val MULTI_TURN: String =
        "<bos><start_of_turn>user\n" +
            "What is 2+2?<end_of_turn>\n" +
            "<start_of_turn>model\n" +
            "Four.<end_of_turn>\n" +
            "<start_of_turn>user\n" +
            "And 3+3?<end_of_turn>\n" +
            "<start_of_turn>model\n"
}

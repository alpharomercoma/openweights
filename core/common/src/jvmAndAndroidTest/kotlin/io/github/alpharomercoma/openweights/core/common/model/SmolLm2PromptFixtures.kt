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
 * Prompts rendered by the `chat_template` that HuggingFaceTB/SmolLM2-1.7B-Instruct ships with.
 *
 * Generated, not written. `tools/executorch/render_reference.py` fetches the template from
 * the Hub and renders it with Jinja over the same conversations `SmolLm2PromptTest` builds,
 * so these strings are what upstream produces rather than anybody's idea of what it should
 * produce. Do not edit by hand: rerun the script and read the diff.
 */
internal object SmolLm2PromptFixtures {
    const val PLAIN: String =
        "<|im_start|>system\n" +
            "You are a helpful AI assistant named SmolLM, trained by Hugging Face<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the capital of Japan?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val WITH_SYSTEM: String =
        "<|im_start|>system\n" +
            "You are a terse assistant.<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the capital of Japan?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val MULTI_TURN: String =
        "<|im_start|>system\n" +
            "You are a helpful AI assistant named SmolLM, trained by Hugging Face<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is 2+2?<|im_end|>\n" +
            "<|im_start|>assistant\n" +
            "Four.<|im_end|>\n" +
            "<|im_start|>user\n" +
            "And 3+3?<|im_end|>\n" +
            "<|im_start|>assistant\n"

    const val SYSTEM_NOT_FIRST: String =
        "<|im_start|>system\n" +
            "You are a helpful AI assistant named SmolLM, trained by Hugging Face<|im_end|>\n" +
            "<|im_start|>user\n" +
            "Hello.<|im_end|>\n" +
            "<|im_start|>system\n" +
            "Be brief from now on.<|im_end|>\n" +
            "<|im_start|>user\n" +
            "What is the capital of Japan?<|im_end|>\n" +
            "<|im_start|>assistant\n"
}

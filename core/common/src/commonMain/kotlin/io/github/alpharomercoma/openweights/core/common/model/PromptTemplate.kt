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
 * Turns a conversation into the exact text one model family was trained to read.
 *
 * Only the ExecuTorch path needs this. A GGUF carries its own template and llama.cpp
 * renders it, so llama.cpp can run a model nobody here has heard of. A `.pte` carries a
 * compiled graph and a tokenizer and nothing else, which means every model on that engine
 * needs its format written out by hand — and is the concrete reason that engine has a
 * curated catalogue while the other one does not.
 */
fun interface PromptTemplate {
    /**
     * @param thinking whether the model may reason before answering. Families that support
     * it spell the switch differently, and a family that does not simply ignores it.
     */
    fun render(messages: List<ChatMessage>, tools: List<ToolDefinition>, thinking: Boolean): String
}

/**
 * Which template a `.pte` needs, worked out from its file name.
 *
 * The name is all there is to go on: a `.pte` has no metadata the app can read, unlike a
 * GGUF's header. That is fragile, so the failure is loud — [forModel] returns null and the
 * engine refuses to load, rather than guessing a format and producing a model that answers
 * slightly wrongly forever.
 */
object PromptTemplates {

    /** The template for [fileName], or null when this build does not know the family. */
    fun forModel(fileName: String): PromptTemplate? {
        val name = fileName.lowercase()
        return when {
            "qwen3" in name -> PromptTemplate { messages, tools, thinking ->
                Qwen3Prompt.render(messages, tools, thinking)
            }
            else -> null
        }
    }

    /** Families this build can render, for an error message that tells the user something. */
    val known: List<String> = listOf("Qwen3")
}

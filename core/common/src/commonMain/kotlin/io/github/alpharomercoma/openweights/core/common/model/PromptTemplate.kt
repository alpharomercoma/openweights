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
interface PromptTemplate {

    /**
     * Text that ends a turn and must never reach the user.
     *
     * llama.cpp knows a model's end-of-turn tokens from the GGUF and stops on them.
     * ExecuTorch streams whatever it decodes, so `<|im_end|>` arrives as ordinary text and
     * was printed at the end of every reply until this existed — measured on device, not
     * reasoned about.
     */
    val stopMarkers: List<String> get() = emptyList()

    /**
     * How much of the tail of [text] could still grow into a stop marker.
     *
     * Streaming has to hold that much back. Fragments do not arrive on marker boundaries,
     * so a marker reaches the caller in pieces, and showing each piece as it lands puts
     * `<|i` on the screen and then removes it.
     */
    fun danglingMarkerLength(text: CharSequence): Int = stopMarkers.maxOfOrNull { marker ->
        val longest = minOf(marker.length - 1, text.length)
        (longest downTo 1).firstOrNull { length ->
            val tail = text.subSequence(text.length - length, text.length)
            marker.startsWith(tail)
        } ?: 0
    } ?: 0

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
            "qwen3" in name -> Qwen3Template
            else -> null
        }
    }

    /** Families this build can render, for an error message that tells the user something. */
    val known: List<String> = listOf("Qwen3")
}

/**
 * [Qwen3Prompt] as a [PromptTemplate].
 *
 * An adapter rather than making that object implement the interface directly, because its
 * `render` carries default arguments an override is not allowed to keep, and those defaults
 * are what `Qwen3PromptTest` calls it through.
 *
 * History is rendered verbatim here, which upstream's template does not do. Only ExecuTorch
 * uses this interface, and that runtime's cache holds what was actually generated: applying
 * the template's history rules would describe a different conversation, the cache would stop
 * matching, and every turn would re-read the whole thing. The trade is that a reply's
 * reasoning stays in the context instead of being dropped once a newer question arrives.
 */
private object Qwen3Template : PromptTemplate {
    /** ChatML's end-of-turn marker, which Qwen3 emits and nothing should show a user. */
    override val stopMarkers: List<String> = listOf("<|im_end|>")

    override fun render(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        thinking: Boolean,
    ): String = Qwen3Prompt.render(messages, tools, thinking, verbatimHistory = true)
}

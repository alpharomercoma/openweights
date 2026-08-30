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
 * Renders a conversation the way Gemma 3's own chat template does.
 *
 * A transcription of `google/gemma-3-1b-it`'s `chat_template`, proved byte-for-byte by
 * `Gemma3PromptTest`. Gemma has no system role: a leading system message becomes a
 * prefix of the first user turn, separated by a blank line. The assistant is called
 * `model`, content is trimmed, and the format knows nothing of tools or thinking.
 *
 * Upstream raises when roles do not alternate user/model. This renders whatever it is
 * given instead: refusing to build a prompt mid-conversation would lose the user's turn,
 * and the model degrades more gracefully than the exception does.
 */
object Gemma3Prompt {

    /**
     * @param includeBos whether to open with `<bos>`. The fixtures carry it; the engine
     * renders without, because ExecuTorch's runner arms BOS itself after every reset.
     */
    fun render(
        messages: List<ChatMessage>,
        addGenerationPrompt: Boolean = true,
        includeBos: Boolean = true,
    ): String = buildString {
        if (includeBos) append("<bos>")
        val leadingSystem = messages.firstOrNull()?.takeIf { it.role == ChatRole.SYSTEM }
        val turns = if (leadingSystem != null) messages.drop(1) else messages
        turns.forEachIndexed { index, message ->
            val role = if (message.role == ChatRole.ASSISTANT) "model" else message.role.wireName
            append("<start_of_turn>").append(role).append('\n')
            if (index == 0 && leadingSystem != null) {
                // The prefix is spliced untrimmed — the template only trims turn content.
                append(leadingSystem.text).append("\n\n")
            }
            append(message.text.trim()).append("<end_of_turn>\n")
        }
        if (addGenerationPrompt) append("<start_of_turn>model\n")
    }
}

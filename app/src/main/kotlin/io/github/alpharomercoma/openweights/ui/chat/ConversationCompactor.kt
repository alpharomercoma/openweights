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

package io.github.alpharomercoma.openweights.ui.chat

import io.github.alpharomercoma.openweights.core.common.context.Compaction
import io.github.alpharomercoma.openweights.core.common.context.CompactionPolicy
import io.github.alpharomercoma.openweights.core.common.context.compactionPrompt
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import javax.inject.Inject

/**
 * Folds older turns of a conversation into a summary using the loaded model.
 *
 * Separate from the view model because it is the one piece of the chat flow with real
 * policy in it, and because summarizing with the same model that is mid-conversation has a
 * subtlety to isolate: the summarization prompt shares no prefix with the chat, so it
 * evicts the KV cache and the cache must be reset afterwards.
 */
class ConversationCompactor @Inject constructor(
    private val engine: InferenceEngine,
    private val policy: CompactionPolicy,
) {
    /** True when the context is full enough that the next turn risks hitting the wall. */
    fun shouldCompact(state: ChatUiState): Boolean = policy.shouldCompact(
        contextUsed = state.contextUsed,
        contextSize = state.contextSize,
        entryCount = state.transcript.size,
    )

    /**
     * Summarizes the foldable range of [state], or returns null when there is nothing to
     * fold or the model produced no usable summary.
     *
     * @param engineIsDecoding whether a generation is actually inside the engine right now.
     *   Asked for rather than read off [ChatUiState], because the flag that looks like the
     *   answer is not one. `isGenerating` means the composer has claimed the turn, which it
     *   does before any suspending work so that two quick taps cannot both start; the fold
     *   that runs before a turn therefore always saw it set. This guard read it anyway and
     *   threw, out of a coroutine with no catch above it and no handler on the scope, so a
     *   conversation reaching the threshold crashed the app on Send. What the guard is
     *   really protecting is the KV cache, and only [ChatViewModel] knows whether anything
     *   is decoding into it.
     */
    suspend fun compact(state: ChatUiState, engineIsDecoding: Boolean): Compaction? {
        check(!engineIsDecoding) { "compaction would reset the context mid-generation" }
        val range = policy.foldRange(
            entryCount = state.transcript.size,
            alreadyFoldedThrough = state.compaction?.foldedThroughIndex ?: -1,
        ) ?: return null

        // Feed the previous summary back in, or a second compaction would produce a
        // summary covering only the newly folded turns while claiming to cover everything
        // before them.
        val summary = summarize(
            previousSummary = state.compaction?.summary,
            entries = state.transcript.slice(range),
        )

        // The summarization call has replaced the conversation in the KV cache. Clear it
        // rather than let the next turn discover the mismatch and silently re-decode.
        engine.resetContext()

        if (summary.isNullOrEmpty()) return null
        return Compaction(
            summary = summary,
            foldedThroughIndex = range.last,
            foldedEntryCount = range.count(),
        )
    }

    private suspend fun summarize(
        previousSummary: String?,
        entries: List<TranscriptEntry>,
    ): String? {
        val turns = entries.joinToString("\n\n") { entry ->
            "${entry.role.wireName}: ${entry.answer.ifEmpty { entry.text }}"
        }
        val transcript = if (previousSummary.isNullOrBlank()) {
            turns
        } else {
            "Summary so far:\n$previousSummary\n\nContinued conversation:\n$turns"
        }
        val request = listOf(ChatMessage.text(ChatRole.USER, compactionPrompt(transcript)))

        return runCatching {
            buildString {
                engine.chat(request, SUMMARY_PARAMS).collect { event ->
                    if (event is GenerationEvent.Token) append(event.text)
                }
            }
        }.getOrNull()
            // A reasoning model will think out loud here too; only the answer is the summary.
            ?.let { parseAssistantReply(it).answer.trim() }
    }

    private companion object {
        /** Low temperature: a summary that invents detail is worse than no summary. */
        val SUMMARY_PARAMS = SamplerParams(temperature = 0.2f, maxTokens = 400)
    }
}

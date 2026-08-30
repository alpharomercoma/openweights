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

import android.util.Log
import io.github.alpharomercoma.openweights.core.common.context.Compaction
import io.github.alpharomercoma.openweights.core.common.context.CompactionPolicy
import io.github.alpharomercoma.openweights.core.common.context.compactionPrompt
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.StopReason
import kotlinx.coroutines.CancellationException
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
        foldableTokens = state.foldableTokens(),
        triggerFraction = state.preferences.compactAtPercent / 100f,
    )

    /**
     * What a fold would actually free: the turns it would replace, less the summary that
     * replaces them.
     *
     * Estimated from the same measured characters-per-token ratio the fold threshold uses,
     * so the two agree about how big a conversation is. Zero when there is nothing to fold,
     * which is the honest answer and the one that stops a pointless fold.
     *
     * An undercount when an engine-history record is standing: the record keeps each
     * question's tool-notes decoration and the tool rounds between question and answer,
     * none of which the transcript's text lengths see, so a fold frees more than this
     * claims. That errs cautious — folding later, never sooner — and the 75%-full trigger
     * reads the engine's own contextUsed, which counts everything, so a genuinely full
     * window still folds on time.
     */
    private fun ChatUiState.foldableTokens(): Int {
        val range = policy.foldRange(
            entryCount = transcript.size,
            alreadyFoldedThrough = compaction?.foldedThroughIndex ?: -1,
        ) { index -> transcript[index].role == ChatRole.ASSISTANT } ?: return 0
        val chars = transcript.slice(range).sumOf { it.text.length }
        val removed = (chars / charsPerToken()).toInt()
        // What the summary will occupy once it is in the prompt, which is the prose after
        // the thinking rather than the generation budget above it. Measured at about 230
        // tokens; rounded up, because guessing high here only makes folding more cautious.
        return (removed - EXPECTED_SUMMARY_TOKENS).coerceAtLeast(0)
    }

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
        ) { index -> state.transcript[index].role == ChatRole.ASSISTANT } ?: return null

        // Feed the previous summary back in, or a second compaction would produce a
        // summary covering only the newly folded turns while claiming to cover everything
        // before them.
        val summary = summarize(
            previousSummary = state.compaction?.summary,
            entries = state.transcript.slice(range),
            budget = MAX_SUMMARY_TOKENS,
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
        budget: Int,
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

        var reason: StopReason? = null
        return try {
            buildString {
                engine.chat(request, SUMMARY_PARAMS.copy(maxTokens = budget)).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> append(event.text)
                        is GenerationEvent.Completed -> reason = event.reason
                    }
                }
            }
                // A summary that stopped because it ran out of room is a fragment, and a
                // fragment is the one thing this must never store: the turns it claims to
                // cover are dropped from every future prompt, so a summary cut mid-sentence
                // silently deletes whatever it had not reached yet. The budget below was
                // sized by measurement to avoid this, and measurement is not a guarantee —
                // a longer conversation, a different template or a model that thinks more
                // reaches the ceiling the table never tested.
                //
                // Refused rather than salvaged. The caller treats null as "not folded",
                // which leaves the real turns in the conversation: worse for the window and
                // honest about what is in it. Half a summary is neither.
                .takeIf { reason == StopReason.END_OF_TURN }
        } catch (cancellation: CancellationException) {
            // Stop pressed, or the screen gone. `runCatching` read that as the summary
            // having failed, so a fold the user interrupted came back as a fold that could
            // not be done, and the caller carried on deciding what to do about it inside a
            // coroutine that was already dead.
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            Log.w("OpenWeights", "a conversation could not be summarised", failure)
            null
        }
            // A reasoning model will think out loud here too; only the answer is the summary.
            ?.let { parseAssistantReply(it).answer.trim() }
    }

    private companion object {
        /**
         * Low temperature: a summary that invents detail is worse than no summary.
         *
         * And no thinking. A summary is a transcription job, not a reasoning one, and on the
         * models this app recommends the thinking is most of the cost: 541 generated tokens
         * against 132 for the same summary with the block closed. The engine honours this
         * even on a template that opens the block regardless, by closing it in the prompt.
         */
        val SUMMARY_PARAMS = SamplerParams(
            temperature = 0.2f,
            maxTokens = MAX_SUMMARY_TOKENS,
            thinking = false,
        )

        /**
         * How long the summarisation call may run for, which is not how long the summary is.
         *
         * Nearly all of this budget is spent thinking. LFM2.5's template pre-opens a `<think>`
         * block, so the reply starts inside one, and `parseAssistantReply` can only take the
         * part after `</think>` if a `</think>` ever arrives. Probed against the real model on
         * the real compaction prompt:
         *
         * | budget | tokens used | closed the block | summary kept |
         * | ---: | ---: | --- | ---: |
         * | 204 | 204, capped | **no** | 0 characters, the whole reply was thinking |
         * | 400 | 400, capped | yes | 220 characters, cut off mid sentence |
         * | 900 | 541, finished | yes | **917 characters, complete** |
         *
         * So the shipped 400 was storing a fragment, and scaling the budget down to fit small
         * windows stored the model's chain of thought verbatim and called it a summary of the
         * conversation. Both were invisible: something plausible-looking went into the system
         * message either way.
         *
         * A generous budget costs nothing when it is not needed, because generation stops at
         * the end of turn: 541 of 900 were used here. It only ever matters when it truncates,
         * and truncating is the failure. Larger is therefore strictly safer, and it makes the
         * summary that reaches the context *smaller*, 230 tokens of prose rather than 530 of
         * cut-off reasoning.
         *
         * A model that does not think will stop far earlier and never see this number.
         */
        const val MAX_SUMMARY_TOKENS = 768

        /** What lands in the prompt, once the thinking has been taken off the front. */
        const val EXPECTED_SUMMARY_TOKENS = 280

        /**
         * Characters to a token when nothing has been measured yet.
         *
         * The same pessimism [ChatUiState.estimatedPromptTokens] uses: a low ratio reads the
         * conversation as bigger than it is, which errs towards folding rather than towards
         * running out of room.
         */
        const val ASSUMED_CHARS_PER_TOKEN = 3f
    }

    private fun ChatUiState.charsPerToken(): Float =
        charsPerToken?.takeIf { it > 0f } ?: ASSUMED_CHARS_PER_TOKEN
}

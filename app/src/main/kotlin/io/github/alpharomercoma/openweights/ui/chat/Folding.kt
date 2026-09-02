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
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Folding older turns into a summary once the context window gets tight.
 *
 * Running out of context is what kills a long conversation, and it always happens mid-answer.
 * Folding between turns instead means the chat simply continues: the full transcript stays on
 * screen and only what is sent to the model shrinks.
 *
 * Its own object because it is its own job, and because [ChatViewModel] has now been told
 * three times that it is too large. Folding needs the summariser, the write queue and the
 * screen, and nothing else: not the engine's lifetime, not the drawer, not the turn loop.
 */
internal class Folding(
    private val compactor: ConversationCompactor,
    private val writer: ChatWriter,
    private val state: MutableStateFlow<ChatUiState>,
) {
    /**
     * Folds if the window needs it, or because the user asked.
     *
     * Returns whether a fold was applied, because a fold rewrites the prompt from the
     * root and the caller may want to read the rewritten prompt back into the engine's
     * cache while nobody is waiting.
     *
     * @param conversationId read rather than passed, and read twice. Folding runs the model,
     * which takes long enough for the user to open another chat, and applying chat A's
     * summary to chat B would corrupt both.
     */
    suspend fun fold(
        force: Boolean,
        engineIsDecoding: Boolean,
        conversationId: () -> Long?,
    ): Boolean {
        val current = state.value
        if (current.isCompacting || (!force && !compactor.shouldCompact(current))) return false
        // Cheaper first. A fold costs a summary written by the model and a re-read of the
        // rewritten prompt; letting the tool observations go costs only the re-read.
        if (!force && maskObservations(current)) return false

        val startedIn = conversationId()

        state.update { it.copy(isCompacting = true) }
        val compaction = try {
            compactor.compact(current, engineIsDecoding = engineIsDecoding)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            // Never fatal. This runs before the try inside the turn opens, in a coroutine
            // with no exception handler on its scope, so anything thrown here reached the
            // platform's default handler and took the process with it. Folding is an
            // optimisation; the conversation is still on screen and the turn can still be
            // attempted, so a failure to fold is worth saying and nothing more.
            state.update { it.copy(error = failure.userMessage()) }
            null
        } finally {
            // In a finally: a model that fails mid-summary would otherwise leave "folding
            // earlier turns" on screen forever, and block every later fold.
            state.update { it.copy(isCompacting = false) }
        }

        if (compaction == null || startedIn != conversationId()) return false

        // Without this a folded chat reopens with no summary and re-sends the whole
        // transcript, which walks straight back into the context wall it just escaped.
        startedIn?.let { id ->
            writer.inOrder {
                saveCompaction(
                    conversationId = id,
                    summary = compaction.summary,
                    throughIndex = compaction.foldedThroughIndex,
                    // Recorded with the summary, because a summary is only as good as what
                    // wrote it and a conversation can change model halfway.
                    modelName = state.value.modelName,
                )
            }
        }

        state.update { current ->
            val folded = current.copy(
                compaction = compaction,
                // The record described a prompt this fold just rewrote from the root. A
                // record captured by a turn *after* the fold contains the recap and is a
                // valid extension again; clearing here is what lets its presence later
                // mean "captured post-fold" without a marker.
                engineHistory = null,
                // The pages the old turns fetched are out of the window with them; the
                // suspicion they earned goes too, except where a note still carries one.
                toolNotes = current.toolNotes.folded(),
                transcript = current.transcript.mapIndexed { index, entry ->
                    if (index == compaction.foldedThroughIndex + 1) {
                        entry.copy(compactionNote = COMPACTION_NOTE)
                    } else {
                        entry
                    }
                },
            )
            // Not zero, which is what the cache holds and not what the next turn will. Folding
            // frees most of the window and never all of it: the summary and the turns kept
            // verbatim are sent again every turn. Reporting zero told everything that sizes
            // itself against the window that the whole of it was free, and the next attachment
            // was measured against a window that was not there.
            folded.copy(contextUsed = folded.estimatedPromptTokens())
        }
        return true
    }

    /**
     * Lets the engine's record of the tool rounds go, when that alone makes room.
     *
     * The record replays every tool result verbatim into every prompt, which is what keeps
     * the cache extending and is also most of what fills the window in a conversation
     * that used tools. Rebuilt from the transcript instead, the next prompt carries each
     * result's head in the tool notes and nothing else of it: the observation replaced by
     * a placeholder, which "The Complexity Trap" (arXiv 2508.21433) finds matches or beats
     * a written summary at half the cost. That costs one full re-read, which a fold costs
     * too, and saves the summary, which on this hardware is twenty to thirty seconds.
     *
     * Only when it is enough: a record whose observations are not what filled the window
     * would be dropped for nothing, and the fold would follow anyway. Measured on the host
     * against the fold policy; the phone's number for the saved seconds is still owed.
     */
    private fun maskObservations(current: ChatUiState): Boolean {
        val record = current.engineHistory ?: return false
        val observations = record.messages
            .filter { it.role == ChatRole.TOOL }
            .sumOf { it.text.length }
        val recorded = record.messages.sumOf { it.text.length }
        // Only where the observations are a real share of what is recorded. Dropping a
        // record that is mostly conversation would free little and cost the re-read, and
        // the estimate below has to be wrong by a lot before a fold is postponed for it.
        if (observations * OBSERVATION_SHARE < recorded) return false
        val masked = current.copy(engineHistory = null)
        val estimate = masked.estimatedPromptTokens()
        if (compactor.shouldCompact(masked.copy(contextUsed = estimate))) return false
        Log.i(
            "OpenWeights",
            "fold: tool observations let go instead, ${current.contextUsed} -> ~$estimate tokens",
        )
        state.update { it.copy(engineHistory = null, contextUsed = estimate) }
        return true
    }

    private companion object {
        /** Observations must be at least this fraction of the record: one part in four. */
        const val OBSERVATION_SHARE = 4
    }
}

internal const val COMPACTION_NOTE = "Earlier turns folded into a summary to make room."

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
import io.github.alpharomercoma.openweights.core.data.db.ConversationMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What is typed in the drawer's search box and what it found. */
data class ChatSearchState(
    val query: String = "",
    val results: List<ConversationMatch> = emptyList(),
    /**
     * Whether [results] is the answer to [query], rather than the answer to what was typed
     * before it or to nothing at all.
     *
     * Without this the drawer said "No chat mentions that" for the length of the debounce on
     * every first keystroke, because an empty result list and a result list that is empty
     * are the same value and only this tells them apart.
     */
    val hasAnswer: Boolean = false,
) {
    /** True while a search is on, which is what decides whether the drawer shows results. */
    val isSearching: Boolean get() = query.isNotBlank()
}

/**
 * Searching every conversation, by title and by anything said in one.
 *
 * Its own object and its own flow, collected beside the chat state rather than folded into
 * it. That is the shape the plan and the pending question already use, and the reason is the
 * same: this is a second thing that changes at its own pace, and a copy inside `ChatUiState`
 * would be a second thing to keep in step for no gain. It also keeps the view model at the
 * size static analysis will accept, which it has been at since before this existed.
 *
 * The scope belongs to the view model, so a search in flight is cancelled when the screen
 * goes, and this object owns nothing it cannot let go of.
 */
class ChatSearch(private val writer: ChatWriter, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ChatSearchState())
    val state: StateFlow<ChatSearchState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Conversations deleted while this drawer has been open, so a read still in flight
     * cannot put one back. See [forget].
     */
    private val forgotten = mutableSetOf<Long>()

    /**
     * Searches for [text], after a pause, and puts what it finds on screen.
     *
     * The text lands immediately so the field never lags the finger; only the read waits.
     * The pending read is cancelled by the next keystroke, so a slow answer for "ka" cannot
     * arrive after a fast one for "kv cache" and leave the wrong rows showing.
     */
    fun search(text: String) {
        _state.update { it.copy(query = text, hasAnswer = false) }
        job?.cancel()
        if (text.isBlank()) {
            _state.update { it.copy(results = emptyList(), hasAnswer = false) }
            return
        }
        job = scope.launch {
            delay(PAUSE_MS)
            val found = try {
                writer.search(text)
            } catch (cancellation: CancellationException) {
                // Rethrown rather than swallowed. runCatching treats it as an ordinary
                // failure, which both logged a warning on every keystroke and let a cancelled
                // read carry on to write its answer over a newer one.
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                // A database that will not open must not take the drawer with it. The list
                // beside this box is still readable, and finding nothing is a survivable
                // answer where a crash is not.
                Log.w("OpenWeights", "the chat search could not be read", failure)
                return@launch
            }
            // Matched against the exact text this read was for, not merely against "something
            // is still typed". A slow answer for "ai" must not land under "llm", which it did
            // whenever the second search was launched before the first returned.
            // And against what has been deleted since, for the same reason as forget:
            // a read that began before the delete answers with the row still in it.
            val kept = found.filterNot { it.id in forgotten }
            _state.update {
                if (it.query != text) it else it.copy(results = kept, hasAnswer = true)
            }
        }
    }

    /**
     * Drops one conversation from what is on screen, because it has been deleted.
     *
     * The results are a list rather than a live query, so a deleted row otherwise stayed
     * there: still tappable, opening nothing. This removes it in place rather than searching
     * again, which was the first attempt and was worse twice over. Re-reading resets the
     * answered flag, so the whole list vanished for a debounce and came back; and it raced
     * the delete it was meant to reflect, because deleting the open conversation waits for a
     * running turn to unwind first, and a re-read that got there before the delete did put
     * the row straight back.
     *
     * The id is remembered as well as filtered out, because the list on screen is not the
     * only list there can be: a read launched by the keystroke before the delete is still
     * running, and its answer, landing after this, held the deleted row again. Cancelling
     * that read was the other option and leaves the query unanswered for good — the drawer
     * saying "No chat mentions that" about a search it never finished — so the answer is
     * let through and the row is taken out of it. A deleted id never returns, so the set
     * only ever holds what was deleted while the screen was up.
     */
    fun forget(id: Long) {
        forgotten += id
        _state.update { it.copy(results = it.results.filterNot { row -> row.id == id }) }
    }

    /** Forgets the search, so reopening the drawer is not still filtered by an old one. */
    fun clear() {
        job?.cancel()
        _state.value = ChatSearchState()
    }

    private companion object {
        /**
         * How long typing has to stop before the history is read.
         *
         * The same pause the Hub search uses, for the same reason: a scan per keystroke is
         * work thrown away while somebody is still deciding what they are looking for.
         */
        const val PAUSE_MS = 200L
    }
}

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

package io.github.alpharomercoma.openweights.ui.archive

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.data.ArchivedConversations
import io.github.alpharomercoma.openweights.core.data.db.ConversationMatch
import io.github.alpharomercoma.openweights.ui.chat.ConversationSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the archive screen shows. */
data class ArchiveUiState(
    val conversations: List<ConversationSummary> = emptyList(),
    /**
     * Whether the rows have been read yet.
     *
     * Without it the screen says "Nothing archived" for the length of one database read on
     * every entry, which is a wrong answer shown confidently — the same mistake the drawer's
     * search made before `hasAnswer` existed.
     */
    val loaded: Boolean = false,
    val query: String = "",
    val results: List<ConversationMatch> = emptyList(),
    val hasAnswer: Boolean = false,
    val error: String? = null,
) {
    val isSearching: Boolean get() = query.isNotBlank()
}

/**
 * The archive's own list and its own search.
 *
 * Reads only. Everything that changes a conversation — unarchiving, renaming, deleting —
 * goes through `ChatViewModel`, which is hoisted above the whole navigation graph and
 * already knows the parts that are easy to get wrong: that deleting has to collect the
 * files the messages referred to before the rows naming them are gone, and that acting on
 * the conversation currently open has to leave the screen somewhere sensible. A second
 * implementation of that here would be a second thing to keep correct.
 */
@HiltViewModel
class ArchiveViewModel @Inject constructor(private val archive: ArchivedConversations) :
    ViewModel() {
    private val _uiState = MutableStateFlow(ArchiveUiState())
    val uiState: StateFlow<ArchiveUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            archive.observe()
                .catch { failure ->
                    Log.w("OpenWeights", "the archive could not be read", failure)
                    _uiState.update { it.copy(error = ARCHIVE_UNREADABLE, loaded = true) }
                }
                .collect { rows ->
                    _uiState.update { state ->
                        state.copy(
                            loaded = true,
                            conversations = rows.map {
                                ConversationSummary(
                                    id = it.id,
                                    title = it.title,
                                    modelName = it.modelName,
                                    updatedAt = it.updatedAt,
                                    pinnedAt = it.pinnedAt,
                                    archivedAt = it.archivedAt,
                                )
                            },
                        )
                    }
                }
        }
    }

    /**
     * Searches the archive, after a pause, exactly the way the drawer searches everything.
     *
     * The text lands immediately so the field never lags the finger; only the read waits,
     * and the pending read is cancelled by the next keystroke so a slow answer for "ka"
     * cannot arrive after a fast one for "kv cache".
     */
    fun search(text: String) {
        _uiState.update { it.copy(query = text, hasAnswer = false) }
        job?.cancel()
        if (text.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), hasAnswer = false) }
            return
        }
        job = viewModelScope.launch {
            delay(PAUSE_MS)
            val found = try {
                archive.search(text)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                Log.w("OpenWeights", "the archive search could not be read", failure)
                return@launch
            }
            // Matched against the exact text this read was for, so a slow answer cannot
            // land under a newer query.
            _uiState.update {
                if (it.query != text) it else it.copy(results = found, hasAnswer = true)
            }
        }
    }

    private companion object {
        /** The same pause the drawer's search uses, for the same reason. */
        const val PAUSE_MS = 200L
        const val ARCHIVE_UNREADABLE = "The archive could not be read."
    }
}

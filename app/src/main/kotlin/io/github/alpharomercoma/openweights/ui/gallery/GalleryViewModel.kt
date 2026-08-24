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

package io.github.alpharomercoma.openweights.ui.gallery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.data.GalleryRepository
import io.github.alpharomercoma.openweights.core.generation.GalleryEntry
import io.github.alpharomercoma.openweights.core.generation.GalleryQuery
import io.github.alpharomercoma.openweights.core.generation.GallerySort
import io.github.alpharomercoma.openweights.core.generation.GenerationTask
import io.github.alpharomercoma.openweights.core.generation.matching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the gallery is showing, and what it would show unfiltered.
 *
 * [total] is the whole gallery rather than the filtered list, because a screen showing
 * nothing has to be able to tell the two empty states apart: nothing made yet, which is an
 * invitation, and nothing matching, which is a filter to clear. Every version of this screen
 * that carried one number got that wrong.
 */
data class GalleryUiState(
    val entries: List<GalleryEntry> = emptyList(),
    val query: GalleryQuery = GalleryQuery(),
    val total: Int = 0,
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** Nothing has ever been made, as opposed to nothing matching what was asked for. */
    val hasNothingAtAll: Boolean get() = total == 0
}

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val gallery: GalleryRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    /**
     * The query, kept in saved state so a filter survives the process going away.
     *
     * Rebuilt from the pieces rather than stored as one value, because `SavedStateHandle`
     * takes what a `Bundle` takes and a data class is not that. Four keys is duller than a
     * serializer and cannot fail to read back.
     */
    private val query = MutableStateFlow(savedState.restoredQuery())

    val uiState: StateFlow<GalleryUiState> =
        combine(gallery.observeAll(), query) { all, asked ->
            GalleryUiState(
                entries = all.matching(asked),
                query = asked,
                total = all.size,
                isLoading = false,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
            initialValue = GalleryUiState(query = query.value),
        )

    init {
        // Once, on the way in. The files live in storage Android may clear and a user may
        // clear from Settings, and neither tells the database anything: without this the
        // grid fills with entries that open onto nothing.
        viewModelScope.launch { gallery.prune() }
    }

    fun sortBy(sort: GallerySort) = update { it.copy(sort = sort) }

    /**
     * Turns one modality on or off.
     *
     * A set rather than a single choice, and empty meaning everything. Somebody looking for
     * a picture selects pictures; somebody who then also wants voices selects both rather
     * than being made to choose between one and all.
     */
    fun toggleModality(modality: GenerationTask) = update { current ->
        current.copy(
            modalities = if (modality in current.modalities) {
                current.modalities - modality
            } else {
                current.modalities + modality
            },
        )
    }

    fun toggleFavouritesOnly() = update { it.copy(favouritesOnly = !it.favouritesOnly) }

    fun search(text: String) = update { it.copy(search = text) }

    /** Filters only. The sort is not a filter, and clearing should not reorder the grid. */
    fun clearFilters() = update {
        GalleryQuery(sort = it.sort)
    }

    fun setFavourite(id: Long, favourite: Boolean) {
        viewModelScope.launch { gallery.setFavourite(id, favourite) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { gallery.delete(id) }
    }

    private fun update(change: (GalleryQuery) -> GalleryQuery) {
        val next = change(query.value)
        query.value = next
        savedState[KEY_SORT] = next.sort.name
        savedState[KEY_MODALITIES] = next.modalities.map { it.name }.toTypedArray()
        savedState[KEY_FAVOURITES] = next.favouritesOnly
        savedState[KEY_SEARCH] = next.search
    }

    private fun SavedStateHandle.restoredQuery(): GalleryQuery {
        val sortName = get<String>(KEY_SORT)
        val modalityNames = get<Array<String>>(KEY_MODALITIES).orEmpty()
        return GalleryQuery(
            // A name this build does not know is a query written by a newer one, which a
            // downgrade produces. Read as the default rather than crashing the screen.
            sort = GallerySort.entries.firstOrNull { it.name == sortName } ?: GallerySort.NEWEST,
            modalities = modalityNames
                .mapNotNull { name -> GenerationTask.entries.firstOrNull { it.name == name } }
                .toSet(),
            favouritesOnly = get<Boolean>(KEY_FAVOURITES) ?: false,
            search = get<String>(KEY_SEARCH).orEmpty(),
        )
    }

    private companion object {
        const val KEY_SORT = "gallery.sort"
        const val KEY_MODALITIES = "gallery.modalities"
        const val KEY_FAVOURITES = "gallery.favourites"
        const val KEY_SEARCH = "gallery.search"

        /** Long enough to survive a rotation, short enough not to hold a query open. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}

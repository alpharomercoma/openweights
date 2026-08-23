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

package io.github.alpharomercoma.openweights.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.common.context.Watch
import io.github.alpharomercoma.openweights.core.common.context.WatchState
import io.github.alpharomercoma.openweights.core.data.WatchRepository
import io.github.alpharomercoma.openweights.watch.WatchScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The list of watches, and the two things a person can do to one. */
@HiltViewModel
class WatchViewModel @Inject constructor(
    private val watches: WatchRepository,
    private val scheduler: WatchScheduler,
) : ViewModel() {
    val state: StateFlow<List<Watch>> = watches.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyList())

    /**
     * Stops a watch, and unschedules it in the same breath.
     *
     * Both, in that order, because they can disagree: the row is what the app reads at
     * startup and the schedule is what actually fires. Stopping only the row leaves a job
     * that wakes up, finds a stopped watch and does nothing, which is a battery cost with no
     * effect; unscheduling only the job leaves a watch that comes back on next launch.
     */
    fun stop(watch: Watch) {
        viewModelScope.launch {
            watches.stop(watch.id, WatchState.STOPPED)
            scheduler.cancel(watch.id)
        }
    }

    /** Removes a watch and its history. Stops it first, for the reason above. */
    fun forget(watch: Watch) {
        viewModelScope.launch {
            scheduler.cancel(watch.id)
            watches.forget(watch.id)
        }
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}

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

package io.github.alpharomercoma.openweights.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.common.model.ModelFormat
import io.github.alpharomercoma.openweights.core.data.UsageRepository
import io.github.alpharomercoma.openweights.core.data.UsageSummary
import io.github.alpharomercoma.openweights.model.ModelStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(usage: UsageRepository, modelStore: ModelStore) :
    ViewModel() {
    val uiState: StateFlow<UsageSummary> = usage.observeSummary()
        // The ledger predates second runtimes, so which engine a model ran on is read off
        // what is installed: a usage row whose weights are a .pte on disk ran ExecuTorch.
        .map { summary ->
            val compiled = modelStore.availableModels()
                .filter { ModelFormat.of(it.name) == ModelFormat.PTE }
                .map { it.nameWithoutExtension }
                .toSet()
            summary.copy(
                perModel = summary.perModel.map { row ->
                    if (row.modelName in compiled) row.copy(runtime = "ExecuTorch") else row
                },
            )
        }
        // A database that will not open throws here, in a flow nobody is awaiting, which
        // takes the process rather than the tab. An empty dashboard is a dashboard; a crash
        // on opening one is a phone that cannot run the app.
        .catch { failure ->
            Log.w("OpenWeights", "the usage ledger could not be read", failure)
            emit(UsageSummary())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UsageSummary())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

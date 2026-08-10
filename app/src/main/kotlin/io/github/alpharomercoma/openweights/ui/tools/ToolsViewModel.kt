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

package io.github.alpharomercoma.openweights.ui.tools

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.tools.SearchSettings
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** One row: what it does, where it came from, and whether it is on. */
data class ToolSummary(
    val id: String,
    val name: String,
    val description: String,
    /** Where it came from and what it costs, which is the only difference that matters. */
    val provenance: String,
    val isEnabled: Boolean,
)

data class ToolsUiState(
    val tools: List<ToolSummary> = emptyList(),
    /** The SearXNG instance web search should use, or blank for the built in scraper. */
    val searxUrl: String = "",
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val registry: ToolRegistry,
    private val switches: ToolSwitches,
    private val search: SearchSettings,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ToolsUiState(read(), search.searxUrl))
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    fun setSearxUrl(url: String) {
        search.searxUrl = url
        _uiState.update { it.copy(searxUrl = search.searxUrl) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        switches.setEnabled(id, enabled)
        _uiState.update { it.copy(tools = read()) }
    }

    private fun read(): List<ToolSummary> = registry.all.map { tool ->
        ToolSummary(
            id = tool.definition.name,
            name = tool.definition.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
            description = tool.definition.description,
            provenance = if (tool.alwaysAsk) {
                "Built in · asks before every run"
            } else {
                "Built in · runs without asking"
            },
            isEnabled = switches.isEnabled(tool.definition.name),
        )
    }
}

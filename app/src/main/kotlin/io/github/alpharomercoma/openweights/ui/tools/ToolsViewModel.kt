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

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.tools.GrantState
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
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

/** The folder the file tools work in, named the way the picker showed it. */
data class WorkspaceSummary(val folder: String?, val state: GrantState)

data class ToolsUiState(
    val tools: List<ToolSummary> = emptyList(),
    val workspace: WorkspaceSummary = WorkspaceSummary(null, GrantState.NONE),
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val registry: ToolRegistry,
    private val switches: ToolSwitches,
    private val grant: WorkspaceGrant,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ToolsUiState(read(), workspace()))
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    fun setEnabled(id: String, enabled: Boolean) {
        switches.setEnabled(id, enabled)
        refresh()
    }

    fun chooseFolder(tree: Uri) {
        grant.remember(tree)
        refresh()
    }

    fun forgetFolder() {
        grant.forget()
        refresh()
    }

    /**
     * Re-reads both halves after either changes.
     *
     * The tool rows depend on the folder as well as on the switches, because three of them
     * describe themselves differently, and disappear from the model's view entirely, when
     * there is nowhere to work.
     */
    private fun refresh() = _uiState.update { it.copy(tools = read(), workspace = workspace()) }

    private fun workspace(): WorkspaceSummary =
        WorkspaceSummary(folder = grant.folder?.folderLabel(), state = grant.state())

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

/**
 * The last part of a tree's document id, which is what the folder is called.
 *
 * A tree uri reads `.../tree/primary%3ADocuments%2FNotes`, and its document id decodes to
 * `primary:Documents/Notes`. Only the last name is worth showing: the rest is the volume and
 * the path someone already navigated through in the picker, and repeating it back at them in
 * a settings row is noise.
 */
private fun Uri.folderLabel(): String {
    val id = runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull()
        ?: return lastPathSegment.orEmpty()
    return id.substringAfterLast('/').substringAfterLast(':').ifEmpty { id }
}

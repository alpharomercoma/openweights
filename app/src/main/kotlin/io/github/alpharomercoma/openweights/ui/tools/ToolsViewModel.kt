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

/** One row: what it does, what it costs, and whether it is on. */
data class ToolSummary(
    val id: String,
    val name: String,
    val description: String,
    /**
     * Whether using it sends anything off the phone.
     *
     * The one property the screen groups by. Everything else about a tool is detail; this
     * is the difference somebody has to decide about.
     */
    val leavesTheDevice: Boolean,
    /** False while it has nothing to work with, which today means no folder has been shared. */
    val isReady: Boolean,
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

    private fun read(): List<ToolSummary> = registry.all
        // Plan mode's own two tools are in the registry because the model is offered them,
        // not because anybody grants them. A switch beside "Advance" did what it said and
        // quietly broke plan mode.
        .filter { it.isUserFacing }
        .map { tool ->
            ToolSummary(
                id = tool.definition.name,
                name = LABELS[tool.definition.name]?.first
                    ?: tool.definition.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                description = LABELS[tool.definition.name]?.second
                    ?: tool.definition.description,
                leavesTheDevice = tool.leavesTheDevice,
                isReady = tool.isAvailable,
                isEnabled = switches.isEnabled(tool),
            )
        }
}

/**
 * What each tool is called on the screen, and what it does in one line.
 *
 * Separate from [ToolDefinition], whose name and description are written for the model and
 * read like it: "Use the path returned by search_files" is exactly right in a prompt and
 * nonsense in a settings row. Deriving the label from the id gave "Fetch url".
 *
 * A tool with no entry falls back to its definition, so a new one shows up in the list
 * looking rough rather than not showing up at all.
 */
private val LABELS: Map<String, Pair<String, String>> = mapOf(
    "web_search" to ("Search the web" to "Looks up anything recent, or anything it does not know."),
    "fetch_url" to ("Open a page" to "Reads the text of one public page."),
    "search_files" to ("Find a file" to "Looks through the folder you shared."),
    "read_file" to ("Read a file" to "Opens a file from that folder."),
    "write_file" to ("Save a file" to "Writes a new file there. It never replaces one."),
    "run_script" to (
        "Run a script" to "Works out sums and dates by running JavaScript. No files, no network."
        ),
)

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

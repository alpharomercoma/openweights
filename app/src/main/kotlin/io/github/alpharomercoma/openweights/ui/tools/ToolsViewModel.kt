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

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.tools.GrantState
import io.github.alpharomercoma.openweights.core.tools.Memory
import io.github.alpharomercoma.openweights.core.tools.Remembered
import io.github.alpharomercoma.openweights.core.tools.SearchEngine
import io.github.alpharomercoma.openweights.core.tools.SearchSettings
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    /** Which engines search may use, in the order they are tried. */
    val engines: List<EngineSummary> = emptyList(),
    val proxy: String = "",
    /** What the app has saved about the user, oldest first, for reading and pruning here. */
    val memories: List<Remembered> = emptyList(),
)

/** One search engine, and whether the user has left it on. */
data class EngineSummary(
    val engine: SearchEngine,
    val enabled: Boolean,
    /**
     * False for the last one left on.
     *
     * A search tool with no engine behind it reports that the web is unreachable, and a
     * model reads that as a fact about the web rather than about the settings.
     */
    val canDisable: Boolean,
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val registry: ToolRegistry,
    private val switches: ToolSwitches,
    private val grant: WorkspaceGrant,
    private val search: SearchSettings,
    private val memory: Memory,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(ToolsUiState(read(), workspace(), engines(), search.proxy))
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    init {
        // Collected rather than snapshotted, because the other writer is the model: a fact
        // saved mid-conversation should be on this screen when the user arrives to check.
        viewModelScope.launch {
            memory.facts.collect { facts -> _uiState.update { it.copy(memories = facts) } }
        }
    }

    /** Rewrites one saved fact from the screen; the same gates the model's edit passes. */
    fun updateMemory(old: String, new: String) {
        memory.replace(old, new)
    }

    fun deleteMemory(text: String) = memory.forget(text)

    fun clearMemories() = memory.forgetAll()

    fun setEngineEnabled(engine: SearchEngine, enabled: Boolean) {
        search.setEnabled(engine, enabled)
        refresh()
    }

    fun setProxy(address: String) {
        search.proxy = address
        refresh()
    }

    private fun engines(): List<EngineSummary> {
        val on = search.enabledEngines()
        return SearchEngine.entries.map { engine ->
            EngineSummary(
                engine = engine,
                enabled = engine in on,
                canDisable = on.size > 1 || engine !in on,
            )
        }
    }

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
    private fun refresh() = _uiState.update {
        it.copy(
            tools = read(),
            workspace = workspace(),
            engines = engines(),
            proxy = search.proxy,
        )
    }

    private fun workspace(): WorkspaceSummary =
        WorkspaceSummary(folder = grant.folder?.folderLabel(), state = grant.state())

    private fun read(): List<ToolSummary> = registry.all
        // Plan mode's own two tools are in the registry because the model is offered them,
        // not because anybody grants them. A switch beside "Advance" did what it said and
        // quietly broke plan mode.
        .filter { it.isUserFacing }
        // One row per switch, not per verb: updating and forgetting a memory ride the
        // save switch, and three rows saying almost the same sentence would bury the
        // rows that decide something. See Tool.switchName.
        .filter { it.definition.name == it.switchName }
        .map { tool ->
            ToolSummary(
                id = tool.definition.name,
                name = LABELS[tool.definition.name]?.first?.let(context::getString)
                    ?: tool.definition.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                description = LABELS[tool.definition.name]?.second?.let(context::getString)
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
private val LABELS: Map<String, Pair<Int, Int>> = mapOf(
    "web_search" to (R.string.tool_search_name to R.string.tool_search_detail),
    "fetch_url" to (R.string.tool_page_name to R.string.tool_page_detail),
    "find_files" to (R.string.tool_find_file_name to R.string.tool_find_file_detail),
    "search_files" to (R.string.tool_find_file_name to R.string.tool_find_file_detail),
    "read_file" to (R.string.tool_read_file_name to R.string.tool_read_file_detail),
    "write_file" to (R.string.tool_save_file_name to R.string.tool_save_file_detail),
    "run_script" to (R.string.tool_script_name to R.string.tool_script_detail),
    "save_memory" to (R.string.tool_save_memory_name to R.string.tool_save_memory_detail),
    "read_memory" to (R.string.tool_read_memory_name to R.string.tool_read_memory_detail),
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

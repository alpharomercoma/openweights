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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.designsystem.component.readableColumn
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.tools.GrantState
import io.github.alpharomercoma.openweights.core.tools.SearchEngine

/**
 * What the model can do, and what each one costs you.
 *
 * One screen rather than a tab each for tools, skills and servers. The question anyone has
 * is "can it search the web", not "is that a tool or an MCP server", so the format never
 * becomes a destination. What did earn a heading is whether using a tool leaves the device,
 * because that is the only difference between any two of them a user has to act on, and as
 * a line of grey small print at the bottom of a row it was invisible.
 *
 * Called Tools rather than Capabilities, which was too long for a tab, and rather than
 * Context, which already means the window whose fill we show as a percentage two screens
 * away. Harness is the accurate word for what this grows into, the loop and its budget,
 * memory, and the guardrails, but it is jargon and every other tab here is a plain word.
 * A tool is also what all three of these are from the model's side: an MCP server hands
 * it tools, and a skill declares which ones it may reach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    state: ToolsUiState,
    onToggle: (String, Boolean) -> Unit,
    onChooseFolder: (Uri) -> Unit,
    onForgetFolder: () -> Unit,
    onEngineEnabled: (SearchEngine, Boolean) -> Unit = { _, _ -> },
    onProxy: (String) -> Unit = {},
    /**
     * Pops back to the conversation, when this screen was pushed from it.
     *
     * Nullable so the arrow only appears where there is somewhere to go back to, which is
     * also what keeps every existing caller and every screen test compiling while the
     * navigation is being rebuilt around it.
     */
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The top bar applies the status-bar inset itself and the app's navigation bar owns
        // the bottom one, so this scaffold must not add either.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.tools),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        // Two groups, and the split is the only thing about a tool anybody has to decide
        // about. It used to be one flat list where a row that asks Wikipedia sat between two
        // that read the folder you shared, distinguished by a grey line of small print.
        val (offDevice, onDevice) = state.tools.partition { it.leavesTheDevice }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .readableColumn(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.what_model_may_do_while),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            // Above the rows it governs, because three of them can do nothing until it is
            // set and do not appear to the model at all while it is not.
            item {
                WorkspaceCard(
                    workspace = state.workspace,
                    onChosen = onChooseFolder,
                    onForget = onForgetFolder,
                )
            }

            // Below the folder and above the rows, because it governs the two that leave
            // the device in the same way the folder governs the three that do not.
            item {
                SearchCard(
                    engines = state.engines,
                    proxy = state.proxy,
                    onEngineEnabled = onEngineEnabled,
                    onProxy = onProxy,
                )
            }

            if (onDevice.isNotEmpty()) {
                item { GroupHeading(stringResource(R.string.on_this_device)) }
                item { ToolGroup(tools = onDevice, onToggle = onToggle) }
            }

            if (offDevice.isNotEmpty()) {
                item { GroupHeading(stringResource(R.string.leaves_device)) }
                item { ToolGroup(tools = offDevice, onToggle = onToggle) }
            }

            item {
                // Said plainly rather than shown as a disabled row, because an empty
                // "Add" affordance that does nothing is worse than an honest sentence.
                Text(
                    text = stringResource(R.string.skill_files_remote_servers_supported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** Enough to read as waiting rather than as broken. */
private const val DIMMED = 0.55f

/** The label over a group. Quiet, because the rows under it are the content. */
@Composable
private fun GroupHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

/**
 * One card per group, with hairlines between the rows.
 *
 * Rather than a card per tool with a gap between each. Six free-floating cards read as six
 * unrelated things; one card with rules in it reads as a list, which is what it is, and the
 * heading above then clearly governs everything inside rather than only the first row.
 */
@Composable
private fun ToolGroup(tools: List<ToolSummary>, onToggle: (String, Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        tools.forEachIndexed { index, tool ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = Dp.Hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
            ToolRow(tool = tool, onToggle = { on -> onToggle(tool.id, on) })
        }
    }
}

/**
 * Where the file tools get somewhere to work.
 *
 * The launcher lives here rather than on the screen so the screen stays a function of its
 * state and a pair of callbacks, which is what makes it testable without an activity. The
 * picker itself is the system's, so nothing here asks for a permission: the folder someone
 * taps is the whole of what the app may reach.
 */
@Composable
private fun WorkspaceCard(
    workspace: WorkspaceSummary,
    onChosen: (Uri) -> Unit,
    onForget: () -> Unit,
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        it?.let(onChosen)
    }

    // Outlined rather than filled, which is the whole point of the change: this is a thing
    // you grant, and the filled cards below are things the model can do. Given the same
    // treatment they read as one more tool that happens to have buttons instead of a switch.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .border(
                width = Dp.Hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(Radius.md),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = workspace.folder ?: stringResource(R.string.no_folder_shared),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = workspace.describe(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = { pick.launch(null) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(
                        if (workspace.folder == null) R.string.choose_folder else R.string.change,
                    ),
                )
            }
            if (workspace.folder != null) {
                TextButton(
                    onClick = onForget,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.remove)) }
            }
        }
    }
}

/** What the row says about the folder, which is a different sentence for each way of failing. */
@Composable
private fun WorkspaceSummary.describe(): String = when (state) {
    GrantState.NONE ->
        stringResource(R.string.folder_none)
    GrantState.LOST ->
        stringResource(R.string.folder_lost)
    GrantState.READ_ONLY ->
        stringResource(R.string.folder_readonly)
    GrantState.READ_WRITE ->
        stringResource(R.string.folder_ready)
}

@Composable
private fun ToolRow(tool: ToolSummary, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Dimmed while it has nowhere to work. The switch stays live, because it is the
        // user's preference and they may well want it set before they pick a folder, but a
        // row at full strength beside an "on" switch claims a capability that is not there.
        Column(modifier = Modifier.weight(1f).alpha(if (tool.isReady) 1f else DIMMED)) {
            Text(text = tool.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Only when there is something to say. "Runs without asking" under five of six
            // rows is a line the eye learns to skip, which is the worst thing a warning can
            // become. The ones with nowhere to work say so.
            //
            // There used to be a second case here, for tools that stopped and asked however
            // the agent mode was set. Nothing has done that since the consent gate came out,
            // so the branch could not be reached by any registered tool and only the preview
            // ever showed it. Whether a run is approved is now a property of the turn rather
            // than of the tool, so there is nothing true this row could say about it.
            val note = if (tool.isReady) null else stringResource(R.string.waiting_for_folder)
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Switch(
            checked = tool.isEnabled,
            onCheckedChange = onToggle,
            // Named, because nothing else in this row is attached to it. The switch is its
            // own node and the three lines beside it are others, so a screen reader landing
            // here announced "switch, on" with no way to tell which of six tools it had
            // reached, and no reason to expect the next swipe to say.
            modifier = Modifier.semantics { contentDescription = tool.name },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun ToolsScreenPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        ToolsScreen(
            state = ToolsUiState(
                tools = listOf(
                    ToolSummary(
                        id = "run_script",
                        name = "Run a script",
                        description = "Works out sums and dates by running JavaScript.",
                        leavesTheDevice = false,
                        isReady = true,
                        isEnabled = true,
                    ),
                    ToolSummary(
                        id = "read_file",
                        name = "Read a file",
                        description = "Opens a file from that folder.",
                        leavesTheDevice = false,
                        isReady = false,
                        isEnabled = true,
                    ),
                    ToolSummary(
                        id = "web_search",
                        name = "Search the web",
                        description = "Looks up anything recent, or anything it does not know.",
                        leavesTheDevice = true,
                        isReady = true,
                        isEnabled = true,
                    ),
                ),
            ),
            onToggle = { _, _ -> },
            onChooseFolder = {},
            onForgetFolder = {},
        )
    }
}

/**
 * Which engines search may use, and a proxy for when they refuse.
 *
 * Collapsed to a summary until opened, because most people never touch it and the two rows
 * it governs are already on this screen. Opened, it is four switches and one field.
 */
@Composable
private fun SearchCard(
    engines: List<EngineSummary>,
    proxy: String,
    onEngineEnabled: (SearchEngine, Boolean) -> Unit,
    onProxy: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val on = engines.count { it.enabled }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { open = !open }
            .padding(14.dp),
    ) {
        Text(stringResource(R.string.search_engines), style = MaterialTheme.typography.titleSmall)
        Text(
            // Says what the order means, because it is not the order of index quality and
            // somebody reading the list will otherwise assume it is.
            text = stringResource(R.string.search_engines_status, on, engines.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!open) return@Column

        engines.forEach { summary ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(summary.engine.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(summary.engine.detailResource()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = summary.enabled,
                    // The last one left on cannot be turned off. See EngineSummary.
                    enabled = summary.canDisable,
                    onCheckedChange = { onEngineEnabled(summary.engine, it) },
                )
            }
        }

        var typed by rememberSaveable(proxy) { mutableStateOf(proxy) }
        Text(
            text = stringResource(R.string.proxy),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            // Both halves matter. The scope is a promise about privacy; the caveat stops
            // this reading as a fix for being blocked, which it is not.
            text = stringResource(R.string.proxy_search_only_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            label = { Text(stringResource(R.string.proxy)) },
            placeholder = { Text(stringResource(R.string.proxy_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(Radius.sm),
        )
        TextButton(onClick = { onProxy(typed) }) { Text(stringResource(R.string.save_proxy)) }
    }
}

private fun SearchEngine.detailResource(): Int = when (this) {
    SearchEngine.DUCKDUCKGO -> R.string.engine_duckduckgo_detail
    SearchEngine.BRAVE -> R.string.engine_brave_detail
    SearchEngine.BING -> R.string.engine_bing_detail
    SearchEngine.GOOGLE -> R.string.engine_google_detail
}

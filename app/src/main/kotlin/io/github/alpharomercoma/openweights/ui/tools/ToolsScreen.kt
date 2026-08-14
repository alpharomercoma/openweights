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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.tools.GrantState

/**
 * What the model can do, and what each one costs you.
 *
 * One list rather than a tab each for tools, skills and servers. The question anyone has
 * is "can it search the web", not "is that a tool or an MCP server", so the format is a
 * badge on the row and not a destination. What the badge is really saying is where the
 * thing came from and whether using it leaves the device, which is the only difference
 * between them that a user has to act on.
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
                title = { Text("Tools", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "What the model is allowed to do while answering. Everything " +
                        "here is built in and runs on this device, except where a row " +
                        "says otherwise.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // Above the rows it governs, because three of them can do nothing until it is
            // set and do not appear to the model at all while it is not.
            item {
                WorkspaceRow(
                    workspace = state.workspace,
                    onChosen = onChooseFolder,
                    onForget = onForgetFolder,
                )
            }

            items(state.tools, key = { it.id }) { tool ->
                ToolRow(
                    tool = tool,
                    onToggle = { enabled -> onToggle(tool.id, enabled) },
                )
            }

            item {
                // Said plainly rather than shown as a disabled row, because an empty
                // "Add" affordance that does nothing is worse than an honest sentence.
                Text(
                    text = "Skill files and remote servers are not supported yet. When " +
                        "they are, they will appear here with a badge saying where they " +
                        "came from and whether they leave the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
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
private fun WorkspaceRow(
    workspace: WorkspaceSummary,
    onChosen: (Uri) -> Unit,
    onForget: () -> Unit,
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        it?.let(onChosen)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Shared folder", style = MaterialTheme.typography.titleSmall)
            Text(
                text = workspace.describe(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "On this device · you choose the folder, and can take it back",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (workspace.folder != null) {
            TextButton(onClick = onForget) { Text("Remove") }
        }
        TextButton(onClick = { pick.launch(null) }) {
            Text(if (workspace.folder == null) "Choose" else "Change")
        }
    }
}

/** What the row says about the folder, which is a different sentence for each way of failing. */
private fun WorkspaceSummary.describe(): String = when (state) {
    GrantState.NONE ->
        "None yet. Searching, reading and saving files stay switched off until you pick one."
    GrantState.LOST ->
        "${folder ?: "The folder"} cannot be reached now. Choose it again to carry on."
    GrantState.READ_ONLY ->
        "$folder. It will not take new files, so saving is off and reading still works."
    GrantState.READ_WRITE ->
        "$folder. The model can search it, read from it, and save new files into it."
}

@Composable
private fun ToolRow(tool: ToolSummary, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = tool.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = tool.provenance,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Switch(checked = tool.isEnabled, onCheckedChange = onToggle)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0D0F)
@Composable
private fun ToolsScreenPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        ToolsScreen(
            state = ToolsUiState(
                tools = listOf(
                    ToolSummary(
                        id = "search_wikipedia",
                        name = "Search Wikipedia",
                        description = "Look up people, places and definitions.",
                        provenance = "Built in · asks Wikipedia",
                        isEnabled = true,
                    ),
                    ToolSummary(
                        id = "fetch_url",
                        name = "Read a page",
                        description = "Fetch a public page and read its text.",
                        provenance = "Built in · asks every time",
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

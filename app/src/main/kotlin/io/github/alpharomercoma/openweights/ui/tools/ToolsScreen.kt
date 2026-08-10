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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius

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
    onSearxUrl: (String) -> Unit,
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

            items(state.tools, key = { it.id }) { tool ->
                ToolRow(
                    tool = tool,
                    onToggle = { enabled -> onToggle(tool.id, enabled) },
                )
            }

            item { SearchBackend(url = state.searxUrl, onUrl = onSearxUrl) }

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
 * Where web search actually goes.
 *
 * Left blank it uses the built in scraper, which needs no setup and is the right default
 * for one person asking one question. It is also the weakest link in the app: it reads a
 * search engine's HTML, so it breaks when that HTML changes, and it is rate limited by
 * address, which is a wall you hit quickly if anything asks it questions in parallel.
 *
 * A SearXNG instance you run yourself is the way past both. It has no index of its own, so
 * it does not make the web more searchable, but it moves the blocking to a machine you
 * control, where the JSON API can be switched on and the limiter switched off. The field
 * was in the code before this and had no control anywhere, so nobody could ever set it.
 */
@Composable
private fun SearchBackend(url: String, onUrl: (String) -> Unit) {
    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Search backend", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Leave this empty to use the built in search, which needs no setup. " +
                "Point it at a SearXNG instance you run to get faster and more reliable " +
                "results, and to stop sharing a rate limit with everyone else.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrl,
            singleLine = true,
            placeholder = { Text("https://searx.example.com") },
            label = { Text("SearXNG address") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (url.isNotBlank()) {
            Text(
                text = "Must be https unless it is on this phone, and the instance needs json in " +
                    "its search formats or it answers 403.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
            onSearxUrl = {},
        )
    }
}

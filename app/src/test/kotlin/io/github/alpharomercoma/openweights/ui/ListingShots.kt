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

package io.github.alpharomercoma.openweights.ui

import androidx.compose.runtime.Composable
import io.github.alpharomercoma.openweights.core.hub.HubModel
import io.github.alpharomercoma.openweights.core.hub.HubQuery
import io.github.alpharomercoma.openweights.core.hub.HubSort
import io.github.alpharomercoma.openweights.core.tools.GrantState
import io.github.alpharomercoma.openweights.ui.discover.DiscoverScreen
import io.github.alpharomercoma.openweights.ui.discover.DiscoverUiState
import io.github.alpharomercoma.openweights.ui.tools.ToolSummary
import io.github.alpharomercoma.openweights.ui.tools.ToolsScreen
import io.github.alpharomercoma.openweights.ui.tools.ToolsUiState
import io.github.alpharomercoma.openweights.ui.tools.WorkspaceSummary

/**
 * The two screens outside the conversation that are worth a listing slot.
 *
 * Discover because "any model, not a catalogue" is the claim the whole app rests on and a
 * list of real repository ids is the only way to show it. Tools because an on-device app
 * that can reach the network has to say so before someone installs it, not after, and the
 * screen where every one of them can be switched off is the honest thing to put on a store
 * page.
 *
 * The repositories named here are real and their parameter counts are right, which matters
 * more than it sounds: a screenshot of a model that does not exist is the sort of thing a
 * reviewer notices.
 */
object ListingShots {
    @Composable
    fun discover() = DiscoverScreen(
        state = DiscoverUiState(
            query = HubQuery(text = "gguf", sort = HubSort.TRENDING),
            results = listOf(
                hub("MadeAgents/Hammer2.1-1.5b", downloads = 41_206, likes = 118),
                hub("bartowski/Qwen2.5-1.5B-Instruct-GGUF", downloads = 289_431, likes = 74),
                hub("unsloth/gemma-3-1b-it-GGUF", downloads = 156_884, likes = 203),
                hub("Salesforce/Llama-xLAM-2-1b-fc-r-gguf", downloads = 12_907, likes = 46),
                hub("LiquidAI/LFM2-1.2B-GGUF", downloads = 33_512, likes = 91),
            ),
            // What the phone can actually hold, which is what turns a list into advice.
            parameterCeilingBillions = 8,
        ),
        onQueryChange = {},
        onSearch = {},
        onSortChange = {},
        onFiltersChange = {},
        onPhoneSizedChange = {},
        onClearFilters = {},
        onOpenModel = {},
        onCloseModel = {},
        onContextLengthChange = {},
        onDownload = { _, _ -> },
    )

    @Composable
    fun tools() = ToolsScreen(
        state = ToolsUiState(
            tools = listOf(
                tool(
                    "web_search",
                    "Web search",
                    "Searches the web for current information.",
                    "Leaves the device. DuckDuckGo, then Wikipedia.",
                    enabled = true,
                ),
                tool(
                    "fetch_url",
                    "Read a page",
                    "Reads a page the model or you named.",
                    "Leaves the device. Public internet only.",
                    enabled = true,
                ),
                tool(
                    "search_files",
                    "Search your folder",
                    "Finds files in the folder you shared.",
                    "On device. Never leaves the phone.",
                    enabled = true,
                ),
                tool(
                    "run_script",
                    "Run a calculation",
                    "Evaluates arithmetic the model writes.",
                    "On device. No network, no filesystem.",
                    enabled = false,
                ),
            ),
            workspace = WorkspaceSummary("Documents/Notes", GrantState.READ_WRITE),
        ),
        onToggle = { _, _ -> },
        onChooseFolder = {},
        onForgetFolder = {},
    )

    private fun hub(id: String, downloads: Int, likes: Int) = HubModel(
        id = id,
        downloads = downloads,
        likes = likes,
        isGated = false,
        tags = listOf("gguf", "text-generation"),
        updatedAt = "2026-08-01T00:00:00.000Z",
        pipelineTag = "text-generation",
    )

    private fun tool(
        id: String,
        name: String,
        description: String,
        provenance: String,
        enabled: Boolean,
    ) = ToolSummary(id, name, description, provenance, enabled)
}

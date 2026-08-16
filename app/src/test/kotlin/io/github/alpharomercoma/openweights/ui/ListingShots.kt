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

    /**
     * The Tools screen, with the rows the real one builds.
     *
     * Every string here is the string `ToolsViewModel.read` would produce: the name is the
     * definition's name with its underscore replaced and its first letter raised, the
     * description is the definition's own, and the provenance is one of exactly two
     * sentences chosen by `alwaysAsk`. That is a rule rather than a preference, and the
     * first version of this file broke it in a way worth writing down: it invented friendly
     * names, wrote provenance lines the screen has no way to render, and told the reader
     * that a search goes to "DuckDuckGo, then Wikipedia". Wikipedia was removed as a
     * provider deliberately, it is named nowhere in the privacy policy, and a store
     * screenshot that says a third party receives the user's queries when it receives
     * nothing is the kind of inaccuracy Play treats as misrepresentation.
     *
     * Four rows rather than the registry's eight, because four is what this screen shows
     * with no folder shared and no plan open: `Tool.isAvailable` keeps the file tools out
     * until there is a folder, and `advance` and `ask_user` describe themselves only inside
     * plan mode. The shot is of a first run, which is the state a person reading a store
     * listing is about to be in.
     */
    @Composable
    fun tools() = ToolsScreen(
        state = ToolsUiState(
            tools = listOf(
                tool(
                    "web_search",
                    "Search the web for something that changes or is recent: news, " +
                        "prices, schedules, results, or a named person, product or " +
                        "organisation. Not for definitions, translations, or facts that " +
                        "do not change.",
                    asks = false,
                    enabled = true,
                ),
                tool(
                    "fetch_url",
                    "Fetch a public web page and return its readable text. Use it to read " +
                        "a page whose address you already have, for example one that " +
                        "web_search returned.",
                    // The one tool that asks every time, because the address is the model's
                    // choice rather than the user's.
                    asks = true,
                    enabled = true,
                ),
                tool(
                    "run_script",
                    "Write JavaScript and run it to work something out. Use it for sums, " +
                        "dates and going through data rather than doing it in your head. " +
                        "Return the answer, or leave it as the last expression. Name " +
                        "files to read as inputs['x'].",
                    asks = false,
                    enabled = true,
                ),
                tool(
                    "search_files",
                    "Find files in the folder the user shared. Match names with a pattern " +
                        "like *.md, and optionally give text to look for inside them.",
                    asks = false,
                    enabled = false,
                ),
            ),
            workspace = WorkspaceSummary(null, GrantState.NONE),
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

    /** The same row `ToolsViewModel.read` builds, derived the same way from the name. */
    private fun tool(id: String, description: String, asks: Boolean, enabled: Boolean) =
        ToolSummary(
            id = id,
            name = id.replace('_', ' ').replaceFirstChar { it.uppercase() },
            description = description,
            provenance = if (asks) {
                "Built in · asks before every run"
            } else {
                "Built in · runs without asking"
            },
            isEnabled = enabled,
        )
}

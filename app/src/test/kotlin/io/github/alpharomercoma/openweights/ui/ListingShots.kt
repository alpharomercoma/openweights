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
import io.github.alpharomercoma.openweights.core.data.GrowthPoint
import io.github.alpharomercoma.openweights.core.data.ModelUsage
import io.github.alpharomercoma.openweights.core.data.UsageSummary
import io.github.alpharomercoma.openweights.core.device.DeviceProfile
import io.github.alpharomercoma.openweights.core.engine.ComputeDevice
import io.github.alpharomercoma.openweights.core.engine.ComputeDeviceKind
import io.github.alpharomercoma.openweights.core.hub.HubModel
import io.github.alpharomercoma.openweights.core.hub.HubQuery
import io.github.alpharomercoma.openweights.core.hub.HubSort
import io.github.alpharomercoma.openweights.core.tools.GrantState
import io.github.alpharomercoma.openweights.ui.dashboard.DashboardScreen
import io.github.alpharomercoma.openweights.ui.discover.DiscoverScreen
import io.github.alpharomercoma.openweights.ui.discover.DiscoverUiState
import io.github.alpharomercoma.openweights.ui.settings.SettingsScreen
import io.github.alpharomercoma.openweights.ui.settings.SettingsUiState
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
        onRuntimeChange = {},
        onFiltersChange = {},
        onPhoneSizedChange = {},
        onOfficialOnlyChange = {},
        onRecommendedOnlyChange = {},
        onClearFilters = {},
        onOpenModel = {},
        onCloseModel = {},
        onContextLengthChange = {},
        onDownload = { _, _ -> },
    )

    /**
     * The Tools screen, with the rows the real one builds.
     *
     * Every string here is the string `ToolsViewModel.read` would produce, which now means
     * its `LABELS` table rather than the tool definitions: those are written for the model
     * and read like it, and putting "Use the path returned by search_files" on a store page
     * is worse than saying nothing. That is a rule rather than a preference, and the first
     * version of this file broke it in a way worth writing down: it invented friendly names,
     * wrote provenance lines the screen has no way to render, and told the reader that a
     * search goes to "DuckDuckGo, then Wikipedia". Wikipedia was removed as a provider
     * deliberately, it is named nowhere in the privacy policy, and a store screenshot that
     * says a third party receives the user's queries when it receives nothing is the kind
     * of inaccuracy Play treats as misrepresentation.
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
                    "Search the web",
                    "Looks up anything recent, or anything it does not know.",
                    enabled = true,
                    offDevice = true,
                ),
                tool(
                    "fetch_url",
                    "Open a page",
                    "Reads the text of one public page.",
                    enabled = true,
                    offDevice = true,
                ),
                tool(
                    "run_script",
                    "Run a script",
                    "Works out sums and dates by running JavaScript. No files, no network.",
                    enabled = true,
                ),
                tool(
                    "search_files",
                    "Find a file",
                    "Looks through the folder you shared.",
                    enabled = false,
                    ready = false,
                ),
            ),
            workspace = WorkspaceSummary(null, GrantState.NONE),
        ),
        onToggle = { _, _ -> },
        onChooseFolder = {},
        onForgetFolder = {},
    )

    /**
     * Settings, for the light audit rather than for the listing.
     *
     * The screen with the most Material components on it: a segmented control, a text
     * field, a filled button, a text button and the About lockup. If the paper palette is
     * going to lose a control to its own accent, it loses it here.
     */
    @Composable
    fun settings() = SettingsScreen(
        state = SettingsUiState(
            device = DeviceProfile(
                totalMemoryBytes = 12_884_901_888,
                availableMemoryBytes = 6_442_450_944,
                freeStorageBytes = 84_825_960_448,
                cpuCores = 8,
                socModel = "Snapdragon 8 Gen 3",
                isLowRamDevice = false,
            ),
            computeDevices = listOf(
                ComputeDevice(
                    id = "CPU",
                    description = "8 cores, arm64",
                    kind = ComputeDeviceKind.CPU,
                    totalMemoryBytes = 12_884_901_888,
                ),
            ),
            engineInfo = "llama.cpp b7042",
        ),
        onSelectTheme = {},
        onSaveToken = {},
        onClearToken = {},
    )

    /** Usage, which is one hero number, one week and one table since it was rebuilt. */
    @Composable
    fun usage() = DashboardScreen(
        summary = UsageSummary(
            lifetimePromptTokens = 18_204,
            lifetimeGeneratedTokens = 142_880,
            lifetimeInferenceMs = 8_640_000,
            replies = 214,
            conversations = 19,
            activeDays = 12,
            growth = (0 until 9).map { day ->
                GrowthPoint(
                    day = 20_300L + day,
                    dayTokens = if (day == 3) 0L else 2_000L + day * 900L,
                    cumulativeTokens = 20_000L + day * 4_000L,
                )
            },
            perModel = listOf(
                ModelUsage("Hammer2.1-1.5B-Q4_0", 120_400, 180, 13.8),
                ModelUsage("Qwen2.5-1.5B-Q4_K_M", 22_480, 34, 11.2),
            ),
        ),
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

    /** The same row `ToolsViewModel.read` builds, from the same table of labels. */
    private fun tool(
        id: String,
        name: String,
        description: String,
        enabled: Boolean,
        offDevice: Boolean = false,
        ready: Boolean = true,
    ) = ToolSummary(
        id = id,
        name = name,
        description = description,
        leavesTheDevice = offDevice,
        isReady = ready,
        isEnabled = enabled,
    )
}

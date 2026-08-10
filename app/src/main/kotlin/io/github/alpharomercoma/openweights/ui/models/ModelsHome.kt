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

package io.github.alpharomercoma.openweights.ui.models

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Where models come from and which ones are here, under one tab.
 *
 * Discover used to be its own destination, which cost a fifth of the bottom bar to say
 * "the same list, but not downloaded yet". Merging them frees that slot for tools and puts
 * the two halves of one job next to each other: find a model, then use it.
 *
 * The switch is a tab row inside each screen's own top bar rather than a wrapper above
 * them, because both apply the status-bar inset themselves and stacking anything on top
 * would apply it twice.
 */
enum class ModelsTab(val label: String) {
    INSTALLED("Installed"),
    DISCOVER("Discover"),
}

@Composable
fun ModelsTabs(selected: ModelsTab, onSelect: (ModelsTab) -> Unit) {
    PrimaryTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        ModelsTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = { Text(tab.label, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

/**
 * Remembers which half is showing across configuration changes and tab switches.
 *
 * Hoisted here so that downloading a model can send the user back to Installed, which is
 * where the thing they just started appears.
 */
@Composable
fun rememberModelsTab(): ModelsTabState {
    var tab by rememberSaveable { mutableStateOf(ModelsTab.INSTALLED) }
    return ModelsTabState(tab) { tab = it }
}

class ModelsTabState(val selected: ModelsTab, val select: (ModelsTab) -> Unit)

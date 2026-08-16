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

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.component.formatBytes
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    state: ModelsUiState,
    onUse: (LocalModel) -> Unit,
    onDelete: (LocalModel) -> Unit,
    onCancelDownload: (String) -> Unit,
    /**
     * Pops back to the conversation, when this screen was pushed from it.
     *
     * Nullable so the arrow only appears where there is somewhere to go back to, which is
     * also what keeps every existing caller and every screen test compiling while the
     * navigation is being rebuilt around it.
     */
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * The Installed/Discover switch, drawn under this screen's own title.
     *
     * A slot rather than a parent wrapper because the top bar applies the status-bar
     * inset itself: anything stacked above it would have that inset applied twice.
     */
    tabs: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The top bar applies the status-bar inset itself and the app's navigation bar owns
        // the bottom one, so this scaffold must not add either, doing both is what left the
        // chrome floating away from the edges it belongs to.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Models", style = MaterialTheme.typography.titleMedium)
                            Metric("${formatBytes(state.storageUsedBytes)} on this device")
                        }
                    },
                    navigationIcon = {
                        onBack?.let { back ->
                            IconButton(onClick = back) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                tabs()
            }
        },
    ) { padding ->
        if (state.models.isEmpty() && state.downloads.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No models yet. Open Discover above. It will tell you whether a " +
                        "model runs on this phone before you download it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.downloads, key = { it.key }) { download ->
                DownloadRow(download = download, onCancel = { onCancelDownload(download.key) })
            }
            items(state.models, key = { it.file.absolutePath }) { model ->
                ModelRow(model = model, onUse = { onUse(model) }, onDelete = { onDelete(model) })
            }
        }
    }
}

@Composable
private fun DownloadRow(download: ActiveDownload, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(download.path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)

        when {
            download.error != null -> Text(
                text = download.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            download.isVerifying -> Metric("Verifying checksum…")

            else -> {
                LinearProgressIndicator(
                    progress = { download.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Metric(
                    "${formatBytes(download.bytesDone)} of ${formatBytes(download.bytesTotal)}",
                )
            }
        }

        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ModelRow(model: LocalModel, onUse: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onUse)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.titleSmall)
            Metric(
                listOfNotNull(
                    formatBytes(model.sizeBytes),
                    // "attachments" rather than "images": all this knows is that a
                    // projector was downloaded beside the model, and an audio projector
                    // reads sound. Saying which would mean parsing the projector's header,
                    // and claiming the wrong one is worse than being general.
                    "reads attachments".takeIf { model.isMultimodal },
                ).joinToString(" · "),
            )
        }
        TextButton(
            onClick = onDelete,
            // The accent means "this is the thing to do". Deleting is not, so it
            // takes the error colour: still reachable, never the default read.
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("Delete") }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun ModelsScreenPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        ModelsScreen(
            state = ModelsUiState(
                downloads = listOf(
                    ActiveDownload(
                        repoId = "LiquidAI/LFM2.5-2.6B-GGUF",
                        path = "LFM2.5-2.6B-Q4_K_M.gguf",
                        key = "LFM2.5-2.6B-Q4_K_M.gguf",
                        bytesDone = 800_000_000,
                        bytesTotal = 1_674_454_848,
                    ),
                ),
            ),
            onUse = {},
            onDelete = {},
            onCancelDownload = {},
        )
    }
}

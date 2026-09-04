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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.designsystem.component.Caption
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.component.formatBytes
import io.github.alpharomercoma.openweights.core.designsystem.component.readableColumn
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
     * Nullable so the arrow only appears where there is somewhere to go back to.
     */
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<LocalModel?>(null) }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The top bar applies the status-bar inset itself and the app's navigation bar owns
        // the bottom one, so this scaffold must not add either, doing both is what left the
        // chrome floating away from the edges it belongs to.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.models),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Metric(
                            stringResource(
                                R.string.storage_on_device,
                                formatBytes(state.storageUsedBytes),
                            ),
                        )
                    }
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
        if (state.models.isEmpty() && state.downloads.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .readableColumn()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.models_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .readableColumn(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.notice?.let { notice ->
                item(key = "notice") {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_TEXT_INSET),
                    )
                }
            }
            items(state.downloads, key = { it.key }) { download ->
                DownloadRow(download = download, onCancel = { onCancelDownload(download.key) })
            }
            // Under the name of whoever published it, rather than in the order the files
            // came off disk. Disk order is download order, which is a fact about the past.
            state.grouped.forEach { group ->
                // No heading for a group with nobody to name. A label over everything is
                // not a category, and the one this used to show named something the user
                // had not done.
                group.heading?.let { heading ->
                    item(key = "head:$heading") {
                        PublisherHeading(
                            heading = heading,
                            avatarUrl = group.publisher?.let(state.avatars::get),
                            // Lines the logo up with the text of the rows below, which are
                            // inset by their own padding inside the list's content padding.
                            startPadding = ROW_TEXT_INSET,
                        )
                    }
                }
                items(group.models, key = { it.file.absolutePath }) { model ->
                    ModelRow(
                        model = model,
                        onUse = { onUse(model) },
                        onDelete = { pendingDelete = model },
                    )
                }
            }
        }
    }
    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_model_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_model_message,
                        model.name,
                        formatBytes(model.sizeBytes),
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(model)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.delete)) }
            },
        )
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

            download.isVerifying -> Caption(stringResource(R.string.download_verifying))

            // Before the progress bar, because a job in WorkManager's backoff is not
            // making progress and a bar at 0% says it is. It had already failed four
            // times in the case this was written for, with nothing on screen admitting it.
            download.isRetrying -> Caption(
                stringResource(R.string.download_retrying, download.attemptsFailed),
            )

            else -> {
                LinearProgressIndicator(
                    progress = { download.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Metric(
                    stringResource(
                        R.string.download_progress,
                        formatBytes(download.bytesDone),
                        formatBytes(download.bytesTotal),
                    ),
                )
            }
        }

        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
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
            val badge = stringResource(R.string.reads_attachments).takeIf { model.isMultimodal }
            val runtime = stringResource(
                if (model.isCompiled) R.string.runtime_executorch else R.string.runtime_gguf,
            )
            Metric(
                listOfNotNull(formatBytes(model.sizeBytes), runtime, badge).joinToString(" · "),
            )
            // Surface the projector gap rather than leaving the user to discover why the
            // attachment button never appears. One line, amber-toned via error container,
            // so it reads as a note rather than a failure.
            if (model.looksLikeVlm) {
                Text(
                    text = stringResource(R.string.model_badge_projector_missing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(
            onClick = onDelete,
            // The accent means "this is the thing to do". Deleting is not, so it
            // takes the error colour: still reachable, never the default read.
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text(stringResource(R.string.delete)) }
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

/**
 * A publisher's name, with their logo when the Hub has been asked and answered.
 *
 * The logo is the point: a list of files named after architectures is hard to scan, and the
 * mark people recognise does more than the word beside it. It is also optional, because the
 * lookup needs the network and this app is used without one, so the heading has to read
 * correctly with the picture missing.
 */
@Composable
internal fun PublisherHeading(
    heading: String,
    avatarUrl: String?,
    // The inset the rows under it use. Without this the logo sat flush against the edge of
    // the screen while every model name began twenty density pixels in, which reads as the
    // heading belonging to something else.
    startPadding: Dp = 0.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatarUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(HEADING_LOGO)
                    .clip(RoundedCornerShape(Radius.xs))
                    .border(
                        width = Dp.Hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(Radius.xs),
                    ),
            )
        }
        Text(
            text = heading,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val HEADING_LOGO = 18.dp

/** What a row's own padding insets its text by, which the heading has to match. */
private val ROW_TEXT_INSET = 14.dp

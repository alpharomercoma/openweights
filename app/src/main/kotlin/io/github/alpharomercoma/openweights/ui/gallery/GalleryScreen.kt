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

package io.github.alpharomercoma.openweights.ui.gallery

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.generation.GalleryEntry
import io.github.alpharomercoma.openweights.core.generation.GallerySort
import io.github.alpharomercoma.openweights.core.generation.GenerationTask
import java.util.Locale

/**
 * Everything the phone has made.
 *
 * A grid rather than a list, because most of what is here is a picture and a picture is
 * recognised before it is read. A voice line has no thumbnail, so it gets a tile that says
 * what it is and shows the first words of what was said, which is what somebody scanning for
 * one is actually looking for.
 *
 * The filters are above the grid rather than behind a sheet. There are three of them and
 * they are the whole reason the screen scales past a few dozen entries; putting them one tap
 * away would mean nobody uses them and everybody scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    onSort: (GallerySort) -> Unit,
    onToggleModality: (GenerationTask) -> Unit,
    onToggleFavourites: () -> Unit,
    onSearch: (String) -> Unit,
    onClearFilters: () -> Unit,
    onSetFavourite: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var viewing by remember { mutableStateOf<GalleryEntry?>(null) }
    var pendingDelete by remember { mutableLongStateOf(0L) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.gallery),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.total > 0) {
                            Text(
                                text = stringResource(
                                    R.string.gallery_count,
                                    state.entries.size,
                                    state.total,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    onBack?.let {
                        IconButton(onClick = it) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                },
                actions = { SortMenu(current = state.query.sort, onSort = onSort) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            if (!state.hasNothingAtAll) {
                Filters(
                    state = state,
                    onToggleModality = onToggleModality,
                    onToggleFavourites = onToggleFavourites,
                    onSearch = onSearch,
                )
            }

            when {
                state.isLoading -> Unit
                state.hasNothingAtAll -> NothingMadeYet()
                state.isEmpty -> NothingMatches(onClearFilters)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = TILE_MIN),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.entries, key = { it.id }) { entry ->
                        GalleryTile(
                            entry = entry,
                            onOpen = { viewing = entry },
                            onToggleFavourite = { onSetFavourite(entry.id, !entry.isFavourite) },
                        )
                    }
                }
            }
        }
    }

    val activeViewing = viewing?.let { v -> state.entries.find { it.id == v.id } ?: v }

    activeViewing?.let { entry ->
        GalleryViewer(
            entry = entry,
            onDismiss = { viewing = null },
            onToggleFavourite = { onSetFavourite(entry.id, !entry.isFavourite) },
            onDelete = { pendingDelete = entry.id },
        )
    }

    if (pendingDelete != 0L) {
        val target = pendingDelete
        AlertDialog(
            onDismissRequest = { pendingDelete = 0L },
            title = { Text(stringResource(R.string.delete_output_title)) },
            text = { Text(stringResource(R.string.delete_output_message)) },
            dismissButton = {
                TextButton(onClick = { pendingDelete = 0L }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = 0L
                        viewing = null
                        onDelete(target)
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
        )
    }
}

@Composable
private fun SortMenu(current: GallerySort, onSort: (GallerySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.AutoMirrored.Rounded.Sort,
                contentDescription = stringResource(R.string.sort_by),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GallerySort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.label())) },
                    onClick = {
                        open = false
                        onSort(sort)
                    },
                    trailingIcon = if (sort == current) {
                        {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun Filters(
    state: GalleryUiState,
    onToggleModality: (GenerationTask) -> Unit,
    onToggleFavourites: () -> Unit,
    onSearch: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.query.search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.search_prompts)) },
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.query.favouritesOnly,
                    onClick = onToggleFavourites,
                    label = { Text(stringResource(R.string.favourites)) },
                )
            }
            items(GenerationTask.entries.toList()) { modality ->
                FilterChip(
                    selected = modality in state.query.modalities,
                    onClick = { onToggleModality(modality) },
                    label = { Text(stringResource(modality.label())) },
                )
            }
        }
    }
}

@Composable
private fun GalleryTile(entry: GalleryEntry, onOpen: () -> Unit, onToggleFavourite: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen),
    ) {
        if (entry.modality == GenerationTask.IMAGE) {
            AsyncImage(
                model = entry.artifact.path,
                contentDescription = entry.prompt,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // A voice has no thumbnail, so the tile is the thing itself: what it is, and the
            // first words of what was said, which is what somebody scanning for one reads.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.GraphicEq,
                    contentDescription = stringResource(R.string.modality_speech),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(
            onClick = onToggleFavourite,
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                imageVector = if (entry.isFavourite) {
                    Icons.Rounded.Favorite
                } else {
                    Icons.Rounded.FavoriteBorder
                },
                contentDescription = stringResource(
                    if (entry.isFavourite) R.string.remove_favourite else R.string.add_favourite,
                ),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun GalleryViewer(
    entry: GalleryEntry,
    onDismiss: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDelete: () -> Unit,
) {
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(entry.id, entry.artifact.path) {
        if (entry.modality == GenerationTask.SPEECH) {
            mediaPlayer = runCatching {
                MediaPlayer().apply {
                    setDataSource(entry.artifact.path)
                    prepare()
                    setOnCompletionListener { isPlaying = false }
                }
            }.getOrNull()
        }
        onDispose {
            runCatching {
                mediaPlayer?.run {
                    if (isPlaying) stop()
                    release()
                }
            }
            mediaPlayer = null
            isPlaying = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = Color.White,
                        )
                    }
                    Row {
                        IconButton(onClick = onToggleFavourite) {
                            Icon(
                                imageVector = if (entry.isFavourite) {
                                    Icons.Rounded.Favorite
                                } else {
                                    Icons.Rounded.FavoriteBorder
                                },
                                contentDescription = stringResource(
                                    if (entry.isFavourite) {
                                        R.string.remove_favourite
                                    } else {
                                        R.string.add_favourite
                                    },
                                ),
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = Color.White,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (entry.modality == GenerationTask.IMAGE) {
                        AsyncImage(
                            model = entry.artifact.path,
                            contentDescription = entry.prompt,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(24.dp),
                        ) {
                            IconButton(
                                onClick = {
                                    mediaPlayer?.let { player ->
                                        if (isPlaying) {
                                            player.pause()
                                            isPlaying = false
                                        } else {
                                            player.start()
                                            isPlaying = true
                                        }
                                    }
                                },
                                modifier = Modifier.size(72.dp),
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) {
                                        Icons.Rounded.PauseCircle
                                    } else {
                                        Icons.Rounded.PlayCircle
                                    },
                                    contentDescription = if (isPlaying) {
                                        stringResource(R.string.stop_reading)
                                    } else {
                                        stringResource(R.string.read_aloud)
                                    },
                                    tint = Color.White,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            entry.durationMillis?.let { duration ->
                                Text(
                                    text = String.format(
                                        Locale.getDefault(),
                                        "%.1fs",
                                        duration / 1000.0,
                                    ),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(Color.Black.copy(alpha = OVERLAY_ALPHA))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = entry.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                    val metadata = listOfNotNull(
                        entry.bundleName,
                        entry.backend.ifBlank { null },
                        entry.seed?.let { "seed $it" },
                        if (entry.width != null && entry.height != null) {
                            "${entry.width}x${entry.height}"
                        } else {
                            null
                        },
                        entry.totalMillis.takeIf { it > 0 }?.let { "${it}ms" },
                    ).joinToString(" · ")
                    if (metadata.isNotBlank()) {
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = MUTED_ALPHA),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NothingMadeYet() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.gallery_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NothingMatches(onClearFilters: () -> Unit) {
    // Told apart from an empty gallery on purpose. One is an invitation and the other is a
    // filter to clear, and a screen that says the same thing for both sends somebody looking
    // for work they already did.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.gallery_no_matches),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onClearFilters) { Text(stringResource(R.string.clear_filters)) }
    }
}

/** The smallest a tile can be and still show what a picture is of. */
private val TILE_MIN = 140.dp
private const val SCRIM_ALPHA = 0.92f
private const val OVERLAY_ALPHA = 0.6f
private const val MUTED_ALPHA = 0.7f

internal fun GallerySort.label(): Int = when (this) {
    GallerySort.NEWEST -> R.string.sort_newest
    GallerySort.OLDEST -> R.string.sort_oldest
    GallerySort.FASTEST -> R.string.sort_fastest
    GallerySort.SLOWEST -> R.string.sort_slowest
}

internal fun GenerationTask.label(): Int = when (this) {
    GenerationTask.IMAGE -> R.string.modality_image
    GenerationTask.SPEECH -> R.string.modality_speech
}

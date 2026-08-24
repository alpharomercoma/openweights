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

package io.github.alpharomercoma.openweights.ui.watch

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.context.Watch
import io.github.alpharomercoma.openweights.core.common.context.WatchState

/**
 * Everything the app is checking on its own, and the way to make it stop.
 *
 * The one screen in the app that exists mainly to be a stop button. A feature that runs
 * unattended and spends battery has to be visible and cancellable in one place, or the honest
 * description of it is "a background process the user cannot find".
 *
 * Stopped and failed watches stay on the list rather than disappearing, because what a watch
 * found is usually the reason it existed, and a list that empties itself would throw that
 * away at the moment it became interesting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(
    watches: List<Watch>,
    onStop: (Watch) -> Unit,
    onForget: (Watch) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.watching),
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = if (watches.isEmpty()) {
                        stringResource(R.string.watch_none)
                    } else {
                        stringResource(R.string.watch_running, Watch.MAX_ACTIVE)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            items(watches, key = { it.id }) { watch ->
                WatchRow(watch = watch, onStop = { onStop(watch) }, onForget = { onForget(watch) })
                HorizontalDivider(
                    thickness = Dp.Hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun WatchRow(watch: Watch, onStop: () -> Unit, onForget: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(watch.task, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = watch.cadence(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        watch.lastSummary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (watch.isActive) {
                TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
            }
            TextButton(onClick = onForget) { Text(stringResource(R.string.remove)) }
        }
    }
}

/**
 * One line saying what this watch does and what state it is in.
 *
 * The notification is named for a watch that keeps one up, because that is a cost the user
 * is paying and the only place it is explained. See `WatchScheduler`: Android will not
 * repeat work faster than fifteen minutes without one.
 */
@Composable
private fun Watch.cadence(): String = when (state) {
    WatchState.STOPPED -> stringResource(R.string.watch_stopped, runs)
    WatchState.FAILED -> stringResource(R.string.watch_failed, consecutiveFailures)
    WatchState.ACTIVE -> buildString {
        append(stringResource(R.string.watch_every, everyMinutes))
        if (needsForegroundService) append(stringResource(R.string.watch_with_notification))
        if (runs > 0) append(stringResource(R.string.watch_so_far, runs))
    }
}

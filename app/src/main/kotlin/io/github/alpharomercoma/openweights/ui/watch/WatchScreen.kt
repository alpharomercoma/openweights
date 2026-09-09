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

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.context.Watch
import io.github.alpharomercoma.openweights.core.common.context.WatchState
import io.github.alpharomercoma.openweights.core.designsystem.component.readableColumn
import kotlinx.coroutines.delay

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
        // One clock for the screen, not one per row: every countdown on it is reading the
        // same second, and a timer per row would be four coroutines saying the same thing.
        // It exists only while this screen is composed, so nothing ticks in the background
        // on account of the interface.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val ticking = watches.any { it.isActive }
        LaunchedEffect(ticking) {
            while (ticking) {
                now = System.currentTimeMillis()
                delay(SECOND_MS)
            }
        }
        // Re-read on every tick of the screen's clock, so coming back from the settings
        // page with the switch flipped takes the notice down within the second.
        val wakesOnTime = exactAlarmsAllowed(now)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .readableColumn(),
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
            if (!wakesOnTime && watches.any { it.isActive && it.needsForegroundService }) {
                item { ExactAlarmNotice() }
            }

            items(watches, key = { it.id }) { watch ->
                WatchRow(
                    watch = watch,
                    now = now,
                    onStop = { onStop(watch) },
                    onForget = { onForget(watch) },
                )
                HorizontalDivider(
                    thickness = Dp.Hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun WatchRow(watch: Watch, now: Long, onStop: () -> Unit, onForget: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(watch.task, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = watch.cadence(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The live half, kept apart from the line above because they answer different
        // questions: that one is what this watch is, this one is where it has got to.
        watch.progress(now)?.let { progress ->
            Text(
                text = progress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
 * Whether the system will wake the phone at the moment a fast watch asks for.
 *
 * From Android 14 the answer starts as no for an app that is not an alarm clock, and only
 * the person can change it, on a settings page. Without it a watch under fifteen minutes
 * sleeps on an inexact alarm, which Doze batches and which HyperOS was measured holding for
 * days, so the watch ticks whenever the phone happens to wake rather than when asked.
 *
 * [tick] is unused except to be read: the caller passes its clock so this is re-evaluated
 * each second the screen is up rather than once per composition.
 */
@Composable
private fun exactAlarmsAllowed(@Suppress("UNUSED_PARAMETER") tick: Long): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarms = LocalContext.current.getSystemService<AlarmManager>() ?: return true
    return alarms.canScheduleExactAlarms()
}

/**
 * The notice that the phone will not wake for a fast watch, and the one button that fixes it.
 *
 * A settings page rather than a dialog, because that is the only door Android offers for
 * this permission. Shown only while a fast watch is active, since a person with no such
 * watch has nothing to grant.
 */
@Composable
private fun ExactAlarmNotice() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.watch_exact_alarm_needed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }
            },
        ) {
            Text(stringResource(R.string.watch_exact_alarm_allow))
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
    WatchState.EXPIRED -> stringResource(R.string.watch_expired, runs)
    WatchState.ACTIVE -> buildString {
        append(stringResource(R.string.watch_every, everyMinutes))
        if (needsForegroundService) append(stringResource(R.string.watch_with_notification))
    }
}

/**
 * How far along this watch is, and how long until it looks again and until it stops.
 *
 * Null for a watch that has finished, where the line above already says how it ended.
 *
 * "Due now" rather than a negative countdown, and it is not a rounding detail: a scheduled
 * tick waits on `WorkManager`, which Doze can hold for far longer than the interval, and a
 * fast watch skips a tick whose engine is busy. The moment can pass without the check
 * happening, so the interface says the check is owed rather than promising a time.
 */
@Composable
private fun Watch.progress(now: Long): String? {
    if (!isActive) return null
    return buildString {
        append(stringResource(R.string.watch_progress, runs + 1, Watch.MAX_RUNS).trimStart(' '))
        val untilNext = dueAt - now
        if (untilNext > 0) {
            append(stringResource(R.string.watch_next_in, duration(untilNext, seconds = true)))
        } else {
            append(stringResource(R.string.watch_next_due))
        }
        val untilEnd = expiresAt - now
        if (untilEnd > 0) {
            append(stringResource(R.string.watch_ends_in, duration(untilEnd, seconds = true)))
        } else {
            // The row still says active because only a tick or the next startup writes the
            // ending down. Saying nothing here would leave a watch that is over looking
            // like one that is running.
            append(stringResource(R.string.watch_ends_now))
        }
    }
}

/**
 * A short, spoken-sounding length of time.
 *
 * Seconds are shown for the next check, because a countdown that only moves once a minute
 * looks stuck, and withheld from "stops in", where they would be false precision on
 * something hours away.
 */
@Composable
private fun duration(millis: Long, seconds: Boolean): String {
    val total = (millis / SECOND_MS).coerceAtLeast(0)
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return when {
        hours > 0 -> stringResource(R.string.watch_duration_hm, hours, minutes)
        minutes > 0 && !seconds -> stringResource(R.string.watch_duration_m, minutes)
        minutes > 0 ->
            stringResource(R.string.watch_duration_ms, minutes, total % SECONDS_PER_MINUTE)
        else -> stringResource(R.string.watch_duration_s, total)
    }
}

private const val SECOND_MS = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

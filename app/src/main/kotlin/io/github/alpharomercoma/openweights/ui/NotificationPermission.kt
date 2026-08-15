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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow

/**
 * Asks to post notifications the first time there is something worth being told about.
 *
 * Which is a change of moment rather than of mind. This used to run in `onCreate`, on the
 * argument that a reply takes minutes and by the time one finishes the phone is in a pocket,
 * which is the worst moment for a dialog and the exact moment the notification was for. The
 * argument is right about the end of the wait and wrong about the beginning: asking in
 * `onCreate` puts a system dialog over the first frame a new user ever sees, before they know
 * what the app is, and pauses the activity underneath it.
 *
 * So it asks when the waiting starts. The phone is in the user's hand, because they have just
 * tapped send or started a download, and what the permission is for is the thing they are now
 * waiting on. Nothing is asked of somebody who only opened the app to look at it.
 *
 * Declining costs nothing: [ReplyNotifier] re-checks before every post, so the app simply
 * stays quiet.
 */
@Composable
fun AskAboutNotifications(waiting: Flow<Boolean>) {
    // Below Tiramisu the permission does not exist and posting needs no grant.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    AskOnce(waiting) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * Runs [ask] the first time [waiting] is true, and never again.
 *
 * Split from the launcher so the rule can be tested without a permission dialog, since the
 * rule is the whole of what is worth getting right: once per session, on the way in rather
 * than on the way out, and not at all for somebody who never starts anything.
 *
 * [rememberSaveable] rather than [remember], because a rotation is not a second request. It is
 * deliberately not a stored preference: a fresh launch does ask again, and Android's own rule
 * bounds that at two dialogs, after which the request is a no-op whatever this does. Two asks
 * across two launches is what the platform is designed around, and a preference of ours would
 * add a second thing to be wrong about it.
 */
@Composable
internal fun AskOnce(waiting: Flow<Boolean>, ask: () -> Unit) {
    var asked by rememberSaveable { mutableStateOf(false) }
    val isWaiting by waiting.collectAsState(initial = false)

    LaunchedEffect(isWaiting, asked) {
        if (!isWaiting || asked) return@LaunchedEffect
        asked = true
        ask()
    }
}

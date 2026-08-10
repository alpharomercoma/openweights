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

package io.github.alpharomercoma.openweights

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.alpharomercoma.openweights.core.data.AppearanceRepository
import io.github.alpharomercoma.openweights.core.data.ThemeChoice
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.ThemeMode
import io.github.alpharomercoma.openweights.ui.OpenWeightsApp
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Injected here rather than read from a screen: the appearance has to be known before
     * the first frame, and every screen is inside the theme it decides.
     */
    @Inject
    lateinit var appearance: AppearanceRepository

    /**
     * Registered as a field because that is the only place it is allowed.
     *
     * The result is not acted on: [ReplyNotifier] re-checks the permission every time it
     * posts, so declining just means the app stays quiet.
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askForNotifications()

        setContent {
            // SYSTEM until the stored value arrives, which is one frame at most and is the
            // same thing the app did before anyone could choose.
            val choice by appearance.themeChoice.collectAsStateWithLifecycle(ThemeChoice.SYSTEM)

            OpenWeightsTheme(themeMode = choice.toThemeMode(), dynamicColor = false) {
                OpenWeightsApp()
            }
        }
    }

    /**
     * Asks once, on first launch, for permission to say when an answer is ready.
     *
     * At startup rather than when a reply finishes: by then the phone is usually back in a
     * pocket, which is the worst moment for a permission dialog and the exact moment the
     * notification was for.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * The stored choice as the theme's own type.
 *
 * Two enums rather than one because the design system does not depend on the data layer,
 * and a shared enum would have to live in one of them. The mapping is total, so nothing
 * can be lost between them.
 */
private fun ThemeChoice.toThemeMode(): ThemeMode = when (this) {
    ThemeChoice.SYSTEM -> ThemeMode.SYSTEM
    ThemeChoice.LIGHT -> ThemeMode.LIGHT
    ThemeChoice.DARK -> ThemeMode.DARK
}

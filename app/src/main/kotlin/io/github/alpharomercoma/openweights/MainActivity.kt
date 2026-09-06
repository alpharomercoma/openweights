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

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.alpharomercoma.openweights.core.data.AppearanceRepository
import io.github.alpharomercoma.openweights.core.data.ThemeChoice
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.ThemeMode
import io.github.alpharomercoma.openweights.ui.OpenWeightsApp
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
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
     * A model a notification asked to open, until the chat has taken it.
     *
     * Set from the launching intent and again from `onNewIntent`, which is how the finished
     * download's tap reaches an activity that is already running. Cleared by the shell
     * once the chat has loaded the file, so a rotation does not open it twice.
     */
    private val openModel = MutableStateFlow<File?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after a rotation the intent is the same one, and the
        // chat has already opened what it asked for.
        if (savedInstanceState == null) openModel.value = intent.modelToOpen()

        setContent {
            // SYSTEM until the stored value arrives, which is one frame at most and is the
            // same thing the app did before anyone could choose.
            val choice by appearance.themeChoice.collectAsStateWithLifecycle(ThemeChoice.SYSTEM)

            OpenWeightsTheme(themeMode = choice.toThemeMode(), dynamicColor = false) {
                OpenWeightsApp(
                    openModel = openModel,
                    onModelOpened = { openModel.compareAndSet(it, null) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.modelToOpen()?.let { openModel.value = it }
    }

    private fun Intent?.modelToOpen(): File? =
        this?.getStringExtra(EXTRA_OPEN_MODEL)?.let(::File)?.takeIf { it.isFile }

    companion object {
        /** The absolute path of a model file a notification wants opened in a fresh chat. */
        const val EXTRA_OPEN_MODEL = "io.github.alpharomercoma.openweights.extra.OPEN_MODEL"
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

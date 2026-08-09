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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // SYSTEM until the stored value arrives, which is one frame at most and is the
            // same thing the app did before anyone could choose.
            val choice by appearance.themeChoice.collectAsStateWithLifecycle(ThemeChoice.SYSTEM)

            OpenWeightsTheme(themeMode = choice.toThemeMode(), dynamicColor = false) {
                OpenWeightsApp()
            }
        }
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

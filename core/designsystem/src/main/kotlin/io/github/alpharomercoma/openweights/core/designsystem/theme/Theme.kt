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

package io.github.alpharomercoma.openweights.core.designsystem.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Which color scheme to render, independent of the system setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** True when the app is currently rendering its dark palette. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/**
 * The app theme.
 *
 * @param themeMode the user's explicit light/dark choice, or [ThemeMode.SYSTEM].
 * @param dynamicColor take neutrals and accent from the device wallpaper (Material You).
 *   Off by default: the signal scale carries meaning, and a wallpaper-derived accent would
 *   compete with it.
 */
@Composable
fun OpenWeightsTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Android draws a translucent scrim behind the three button navigation strip
            // unless told not to. With it, the phone's own buttons sit on a slab a shade
            // apart from the app's navigation bar directly above them, and the bottom of
            // the screen reads as two rows of controls rather than one edge. Turning it
            // off lets our bar's colour run to the bottom of the display, which is the
            // whole difference between a bar that ends and a bar that the phone continues.
            window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OpenWeightsTypography,
            shapes = OpenWeightsShapes,
            content = content,
        )
    }
}

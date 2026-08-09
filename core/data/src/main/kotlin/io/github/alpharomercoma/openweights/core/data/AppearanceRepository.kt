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

package io.github.alpharomercoma.openweights.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Which appearance the user picked, independent of what the system is doing. */
enum class ThemeChoice {
    /** Follow the device. The default, and what most people want. */
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Remembers the appearance choice.
 *
 * Its own repository rather than a field on the model preferences: this is the one setting
 * that has to be read before the first frame is drawn, by the activity rather than by any
 * screen, and it belongs to the person rather than to a model.
 */
@Singleton
class AppearanceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val themeChoice: Flow<ThemeChoice> = context.settingsDataStore.data.map { preferences ->
        // A value written by a newer build must not stop an older one starting; following
        // the system is always a safe reading.
        preferences[KEY]?.let { stored ->
            ThemeChoice.entries.firstOrNull { it.name == stored }
        } ?: ThemeChoice.SYSTEM
    }

    suspend fun setThemeChoice(choice: ThemeChoice) {
        context.settingsDataStore.edit { store -> store[KEY] = choice.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("appearance_theme")
    }
}

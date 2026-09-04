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
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException

/**
 * What to do about a settings file that will not parse.
 *
 * DataStore's default is to throw, once per read, forever. The file is written
 * non-atomically enough to be truncated by a kill or a full disk mid-write, and the read
 * that hits it is the theme, on the first frame of `MainActivity`, so an app that had
 * been shut down at the wrong moment could not be started again, and the only cure was
 * clearing its data, which takes the conversations with it.
 *
 * Started over, then. Everything in here is a preference the user can set again in a few
 * taps: the theme, the context window, which model was last open. None of it is worth a
 * launch, and none of it is stored anywhere else. The transcripts are in Room, which
 * fails on its own terms.
 */
internal val settingsCorruption = ReplaceFileCorruptionHandler<Preferences> { failure ->
    Log.w(TAG, "the settings file could not be read and was started over", failure)
    emptyPreferences()
}

/**
 * The app's single preferences store.
 *
 * One store for the whole app: DataStore serialises writes per file, and multiple stores
 * over the same settings is the classic way to get lost updates.
 */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "openweights_settings",
    corruptionHandler = settingsCorruption,
)

/**
 * The same readings, with a store that cannot be read at all answered as an empty one.
 *
 * [settingsCorruption] covers a file whose bytes are not preferences. This covers the
 * other way a read fails (the file cannot be opened, storage has gone away, the disk is
 * full enough that DataStore cannot write its scratch file), which arrives as a plain
 * `IOException` and is just as fatal to whoever is collecting. Every reader of these
 * settings has a defined answer for "nothing is stored", because that is what a fresh
 * install is, so every reader has an answer for this.
 *
 * Only `IOException`, and nothing else: a bug in a mapping above this is not a storage
 * failure and must not be quietly answered with defaults.
 */
internal fun Flow<Preferences>.orEmptyWhenUnreadable(): Flow<Preferences> = catch { failure ->
    if (failure is IOException) {
        Log.w(TAG, "the settings could not be read; using defaults", failure)
        emit(emptyPreferences())
    } else {
        throw failure
    }
}

private const val TAG = "OpenWeights"

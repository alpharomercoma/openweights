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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * What the settings do when they cannot be read.
 *
 * The theme is read by `MainActivity` to draw its first frame, so this is the one
 * preference whose read is on the path to the app existing at all. DataStore's default
 * for a file it cannot parse is to throw, and to throw again on the next read, which
 * turns one bad shutdown into an app that will not start until its data is cleared,
 * taking every conversation with it. Nothing in here is worth that: the theme, the
 * window, the last model are all a few taps to set again.
 */
class SettingsRecoveryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    /** A real store over [file], with the handler the app's own store is built with. */
    private fun storeOver(file: File): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = settingsCorruption,
        scope = scope,
        produceFile = { file },
    )

    @Test
    fun `a settings file that will not parse is started over rather than thrown`() = runTest {
        val file = File(folder.root, "openweights_settings.preferences_pb")
        // Not preferences, and not empty: what a kill or a full disk part-way through a
        // write leaves behind.
        file.writeBytes(ByteArray(96) { (it * 7 + 3).toByte() })

        val choice = AppearanceRepository(storeOver(file)).themeChoice.first()

        assertThat(choice).isEqualTo(ThemeChoice.SYSTEM)
    }

    @Test
    fun `a store that cannot be read at all reads as the default theme`() = runTest {
        // The other way a read fails: the file cannot be opened, storage has gone away,
        // the disk is too full for DataStore's own scratch file. Corruption handling does
        // not cover this one: it arrives as a plain IOException.
        val choice = AppearanceRepository(unreadable(IOException("storage is gone")))
            .themeChoice.first()

        assertThat(choice).isEqualTo(ThemeChoice.SYSTEM)
    }

    @Test
    fun `a failure that is not storage is not answered with a default`() = runTest {
        // A bug in a mapping above this is not a disk that has gone away, and answering it
        // with defaults would hide it forever.
        val failure = runCatching {
            AppearanceRepository(unreadable(IllegalStateException("bug"))).themeChoice.first()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    /** A store whose every read fails with [failure]. */
    private fun unreadable(failure: Throwable) = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw failure }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = throw failure
    }
}

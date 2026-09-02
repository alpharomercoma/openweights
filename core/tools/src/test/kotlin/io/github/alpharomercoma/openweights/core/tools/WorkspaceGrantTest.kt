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

package io.github.alpharomercoma.openweights.core.tools

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver

/**
 * What the grant reports after the system has had its say.
 *
 * Robolectric's resolver takes every permission it is asked for, so the provider that will
 * only hand over a read is played by a shadow of the resolver that refuses the pair the
 * way the real one does: as a whole, not by keeping the half it could give.
 */
@RunWith(RobolectricTestRunner::class)
class WorkspaceGrantTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a folder the picker granted read and write is read-write`() {
        val grant = WorkspaceGrant(context)

        grant.remember(FakeDocumentsProvider.TREE)

        assertThat(grant.state()).isEqualTo(GrantState.READ_WRITE)
    }

    @Test
    @Config(shadows = [ReadOnlyResolver::class])
    fun `a folder the provider will only let the app read is read-only, not lost`() {
        // The combined take was refused, nothing was persisted, and the folder was still
        // written down: state() then said LOST, and kept saying it, for a folder the app
        // could read the whole time.
        val grant = WorkspaceGrant(context)

        grant.remember(FakeDocumentsProvider.TREE)

        assertThat(grant.state()).isEqualTo(GrantState.READ_ONLY)
        assertThat(grant.folder).isEqualTo(FakeDocumentsProvider.TREE)
    }

    /** A resolver over a provider that persists reads and refuses to persist writes. */
    @Implements(ContentResolver::class)
    class ReadOnlyResolver : ShadowContentResolver() {
        @Implementation
        override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
            if (modeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
                throw SecurityException("No persistable write permission grants found")
            }
            super.takePersistableUriPermission(uri, modeFlags)
        }
    }
}

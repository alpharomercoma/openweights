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

package io.github.alpharomercoma.openweights.ui.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.hub.HubTokenSource
import io.github.alpharomercoma.openweights.core.hub.HuggingFaceClient
import io.github.alpharomercoma.openweights.core.hub.Publishers
import io.github.alpharomercoma.openweights.model.ModelStore
import io.github.alpharomercoma.openweights.ui.chat.FakeInferenceEngine
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * What the model list does before anything has ever been downloaded.
 *
 * The one behaviour here worth a test is the one the view model's own comment describes as
 * having shipped. `getWorkInfosByTagFlow` emits nothing at all on a phone that has never
 * queued a download — not an empty list, nothing — and `combine` stays silent until every
 * source has spoken once. So the models already on disk never reached the screen: a fresh
 * install showed "no models yet" with gigabytes sitting in its own directory, and the list
 * appeared only after the first download created a row to report.
 *
 * The fix is one `onStart { emit(emptyList()) }`, which is exactly the kind of line somebody
 * tidies away as redundant. This is the test that stops them.
 *
 * A real WorkManager rather than a fake queue, because the emptiness of that first emission
 * is the whole subject and a hand-written fake would be a fake of the thing that went wrong.
 */
@RunWith(RobolectricTestRunner::class)
class ModelsViewModelTest {
    private lateinit var context: Context
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        store = ModelStore(context)
        store.directory.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `models on disk reach the screen with no download ever queued`() = runTest {
        // The exact shape of the shipped bug: weights present, queue never used.
        gguf("LFM2.5-2.6B-Q4_K_M.gguf")

        viewModel().uiState.test {
            assertThat(awaitItem().models.map { it.name })
                .containsExactly("LFM2.5-2.6B-Q4_K_M")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty directory reports no models rather than never reporting`() = runTest {
        // The counterweight, and the half that made the bug hard to see: "no models" is a
        // real answer, and it is indistinguishable from a screen that never spoke unless
        // something asserts the state arrived.
        viewModel().uiState.test {
            val state = awaitItem()
            assertThat(state.models).isEmpty()
            assertThat(state.downloads).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `storage is the sum of what is on disk`() = runTest {
        // Shown to the user as what the app is occupying, and the only number on that screen
        // they might act on.
        gguf("a.gguf", bytes = 1_024)
        gguf("b.gguf", bytes = 2_048)

        assertThat(settled().storageUsedBytes).isEqualTo(3_072)
    }

    @Test
    fun `a projector is counted with its model rather than listed as one`() = runTest {
        // A projector is not something anybody chose to run, so it must not appear as a
        // model. Its bytes are real, though, and hiding them would understate what the app
        // is using by half a gigabyte on a vision model.
        gguf("vision.gguf", bytes = 4_096)
        gguf("mmproj-vision.gguf", bytes = 1_024)

        val state = settled()

        assertThat(state.models.map { it.name }).containsExactly("vision")
        assertThat(state.storageUsedBytes).isEqualTo(5_120)
    }

    /**
     * The state once something is actually looking at it.
     *
     * `uiState` is a `stateIn(WhileSubscribed)`, so reading `.value` with no collector gives
     * back the empty value it was seeded with rather than anything the combine produced.
     * That is the right behaviour — a screen nobody is watching should not keep a directory
     * listing warm — and it means a test has to subscribe like the screen does. Reading
     * `.value` instead is how two of these first failed, with an empty list that looked
     * exactly like the bug this file exists to catch.
     */
    private suspend fun settled(): ModelsUiState {
        lateinit var latest: ModelsUiState
        // Thirty seconds of ceiling, milliseconds of typical wait: the directory listing
        // runs on real dispatchers, and Turbine's three-second default was missed by the
        // two-core CI runner under a full-suite load while every faster machine passed.
        viewModel().uiState.test(timeout = 30.seconds) {
            latest = awaitItem()
            while (latest.models.isEmpty()) latest = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return latest
    }

    private fun gguf(name: String, bytes: Int = 1_024): File =
        File(store.directory, name).apply { writeBytes(ByteArray(bytes)) }

    /**
     * Publisher lookups never leave the process here.
     *
     * The view model asks for a logo per publisher, best effort, and nothing waits on the
     * answer. With no token and no network the lookup fails and the map stays empty, which
     * is the same state an offline phone is in and the one these tests care about.
     */
    private fun viewModel() = ModelsViewModel(
        WorkManager.getInstance(context),
        store,
        Publishers(HuggingFaceClient(OkHttpClient(), HubTokenSource { null })),
        FakeInferenceEngine(),
        context,
    )
}

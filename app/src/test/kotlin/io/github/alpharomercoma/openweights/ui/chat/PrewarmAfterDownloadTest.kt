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

package io.github.alpharomercoma.openweights.ui.chat

import android.app.ActivityManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.hub.DOWNLOAD_PARTIAL_SUFFIX
import io.github.alpharomercoma.openweights.model.ModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File

/**
 * What a finished download is allowed to do to the engine.
 *
 * The warm state file turns a first turn from a minute into a second, and a model that
 * has just been downloaded is the one case where there is no file to restore. Somebody
 * has to compute it once. Before this, that somebody was whoever sent the first message,
 * on a phone that had just spent minutes downloading and was in the worst state it would
 * ever be in — which is the "31 tok/s prefill, 1 tok/s decode" report this comes from.
 *
 * So a finished download now does what opening the chat tab would have done. These tests
 * pin both halves of that: that it happens at all, and the three cases where being early
 * would be worse than waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PrewarmAfterDownloadTest : ChatFixture() {

    private val store: ModelStore
        get() = ModelStore(ApplicationProvider.getApplicationContext())

    /**
     * The models folder, emptied around every test in here.
     *
     * These are the only tests that write into the store's own directory rather than the
     * fixture's temporary one, because they are about what the store reports and the
     * chat's [ChatViewModel.loadDefaultModel] path reads it directly. Robolectric hands
     * the whole JVM the same external files directory, so a model left behind here is a
     * model every later test class finds installed.
     */
    @Before
    fun emptyTheModelsFolderFirst() = emptyTheModelsFolder()

    @After
    fun emptyTheModelsFolderAfter() = emptyTheModelsFolder()

    private fun emptyTheModelsFolder() {
        store.directory.listFiles()?.forEach { it.delete() }
    }

    /** A model where the store looks for one, rather than in the fixture's own folder. */
    private fun installed(name: String = "downloaded.gguf"): File =
        File(store.directory, name).apply { writeText("not a real model") }

    @Test
    fun `a finished download opens the model before anybody asks for it`() = runTest(dispatcher) {
        val model = installed()

        arrivals.announce(model)
        settle(steps = PREWARM_SETTLE_STEPS)

        assertThat(viewModel.uiState.value.modelName).isNotNull()
        // The point of opening it early is the warm at the end of the load, because
        // that is what writes the state file the next launch restores in milliseconds.
        assertThat(engine.warmCalls).isNotEmpty()
    }

    @Test
    fun `a download that finishes while another is still running waits for it`() =
        runTest(dispatcher) {
            val model = installed()
            // What this stands for is the projector half of a multimodal model, which is a
            // second download and usually the slower one. Opening the weights now would
            // load them without it, and the chat tab only loads a model when it finds
            // none, so the pictures would stay broken for as long as the app was running.
            File(store.directory, "mmproj-downloaded.gguf$DOWNLOAD_PARTIAL_SUFFIX")
                .writeText("half a projector")

            arrivals.announce(model)
            settle(steps = PREWARM_SETTLE_STEPS)

            assertThat(viewModel.uiState.value.modelName).isNull()
            assertThat(engine.warmCalls).isEmpty()
        }

    @Test
    fun `a finished download never swaps the weights under a loaded model`() = runTest(dispatcher) {
        loadModel()
        val loaded = viewModel.uiState.value.modelName
        engine.warmCalls.clear()

        arrivals.announce(installed("something-else.gguf"))
        settle(steps = PREWARM_SETTLE_STEPS)

        assertThat(viewModel.uiState.value.modelName).isEqualTo(loaded)
        assertThat(engine.warmCalls).isEmpty()
    }

    @Test
    fun `a finished download does not undo an unload the user asked for`() = runTest(dispatcher) {
        loadModel()
        viewModel.unloadModel()
        settle(steps = PREWARM_SETTLE_STEPS)
        engine.warmCalls.clear()

        arrivals.announce(installed())
        settle(steps = PREWARM_SETTLE_STEPS)

        assertThat(viewModel.uiState.value.modelName).isNull()
        assertThat(engine.warmCalls).isEmpty()
    }

    @Test
    fun `a download that finishes with the app in a pocket does not load anything`() =
        runTest(dispatcher) {
            // The ordinary case, not the exception: a multi-gigabyte transfer is minutes
            // and nobody watches it. Taking two gigabytes into a process Android has
            // already filed as cached is how that process gets killed.
            backgroundTheApp()

            arrivals.announce(installed())
            settle(steps = PREWARM_SETTLE_STEPS)

            assertThat(viewModel.uiState.value.modelName).isNull()
            assertThat(engine.warmCalls).isEmpty()
        }

    /**
     * Files this process the way Android files one whose UI is gone.
     *
     * The download itself holds a foreground service, and a foreground service ranks
     * below IMPORTANCE_FOREGROUND, so this is the importance a finished download actually
     * reports when the user has switched away.
     */
    private fun backgroundTheApp() {
        val manager = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(ActivityManager::class.java)
        Shadows.shadowOf(manager).setProcesses(
            listOf(
                ActivityManager.RunningAppProcessInfo().apply {
                    pid = Process.myPid()
                    importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                },
            ),
        )
    }

    @Test
    fun `opening a model interrupts the warm instead of queueing behind it`() =
        runTest(dispatcher) {
            loadModel()
            settle(steps = PREWARM_SETTLE_STEPS)

            // A warm parked in its prefill, the way the pre-warm sat on the phone for
            // 8.1 s after a download. Measured there: a tap on the model waited 9.9 s,
            // of which 8.4 s was this. A load is as much a thing somebody is waiting on
            // as a message is, so it interrupts the warm exactly as a turn does.
            viewModel.newChat()
            engine.warmGate = CompletableDeferred()
            settle(steps = 2)
            val cancelsBefore = engine.cancelCount

            viewModel.loadModel(modelFile("second.gguf"))
            settle(steps = PREWARM_SETTLE_STEPS)

            assertThat(engine.cancelCount).isGreaterThan(cancelsBefore)
            assertThat(engine.loads.last()).isEqualTo("second.gguf")
            engine.warmGate = null
        }

    private companion object {
        /** A load reads settings and the ledger before it reaches the engine, then warms. */
        const val PREWARM_SETTLE_STEPS = 40
    }
}

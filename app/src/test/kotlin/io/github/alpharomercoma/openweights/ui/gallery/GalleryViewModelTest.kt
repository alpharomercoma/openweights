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

package io.github.alpharomercoma.openweights.ui.gallery

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.data.GalleryRepository
import io.github.alpharomercoma.openweights.core.data.GeneratedOutputStore
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.generation.Artifact
import io.github.alpharomercoma.openweights.core.generation.GalleryEntry
import io.github.alpharomercoma.openweights.core.generation.GallerySort
import io.github.alpharomercoma.openweights.core.generation.GenerationTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GalleryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: OpenWeightsDatabase
    private lateinit var store: GeneratedOutputStore
    private lateinit var repository: GalleryRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(context, OpenWeightsDatabase::class.java)
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .allowMainThreadQueries()
            .build()
        store = GeneratedOutputStore(context)
        store.directory.listFiles()?.forEach { it.delete() }
        repository = GalleryRepository(database, store)
    }

    @After
    fun tearDown() {
        database.close()
        store.directory.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun made(name: String, bytes: Int = 16): File =
        File(store.directory, name).apply { writeBytes(ByteArray(bytes)) }

    private fun entry(
        file: File,
        task: GenerationTask = GenerationTask.IMAGE,
        prompt: String = "a cat",
        isFavourite: Boolean = false,
        totalMillis: Long = 1000L,
    ) = GalleryEntry(
        artifact = Artifact(
            file.absolutePath,
            if (task ==
                GenerationTask.IMAGE
            ) {
                "image/png"
            } else {
                "audio/wav"
            },
        ),
        modality = task,
        prompt = prompt,
        bundleId = "bundle-1",
        bundleName = "Bundle 1",
        createdAt = 1000L,
        totalMillis = totalMillis,
        backend = "CPU",
        isFavourite = isFavourite,
    )

    @Test
    fun `empty repository produces empty state`() = runTest(dispatcher) {
        val viewModel = GalleryViewModel(repository, SavedStateHandle())

        viewModel.uiState.test {
            advanceUntilIdle()
            val state = awaitItem()
            val loaded = if (state.isLoading) {
                advanceUntilIdle()
                awaitItem()
            } else {
                state
            }
            assertThat(loaded.entries).isEmpty()
            assertThat(loaded.total).isEqualTo(0)
            assertThat(loaded.hasNothingAtAll).isTrue()
            assertThat(loaded.isEmpty).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `filtering by modality updates entries without changing total count`() =
        runTest(dispatcher) {
            repository.record(
                entry(made("img.png"), task = GenerationTask.IMAGE, prompt = "an astronaut"),
            )
            repository.record(
                entry(made("speech.wav"), task = GenerationTask.SPEECH, prompt = "hello world"),
            )
            advanceUntilIdle()

            val viewModel = GalleryViewModel(repository, SavedStateHandle())

            viewModel.uiState.test {
                advanceUntilIdle()
                var state = awaitItem()
                while (state.isLoading || state.total < 2) {
                    advanceUntilIdle()
                    state = awaitItem()
                }

                assertThat(state.total).isEqualTo(2)
                assertThat(state.entries).hasSize(2)

                viewModel.toggleModality(GenerationTask.IMAGE)
                advanceUntilIdle()
                state = awaitItem()
                while (state.entries.size != 1) {
                    advanceUntilIdle()
                    state = awaitItem()
                }

                assertThat(state.total).isEqualTo(2)
                assertThat(state.entries.single().modality).isEqualTo(GenerationTask.IMAGE)
                cancelAndIgnoreRemainingEvents()
            }
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `searching prompt filters the list`() = runTest(dispatcher) {
        repository.record(entry(made("1.png"), prompt = "a red fox"))
        repository.record(entry(made("2.png"), prompt = "a blue bird"))
        advanceUntilIdle()

        val viewModel = GalleryViewModel(repository, SavedStateHandle())

        viewModel.uiState.test {
            advanceUntilIdle()
            var state = awaitItem()
            while (state.isLoading || state.total < 2) {
                advanceUntilIdle()
                state = awaitItem()
            }

            viewModel.search("fox")
            advanceUntilIdle()
            state = awaitItem()
            while (state.entries.size != 1) {
                advanceUntilIdle()
                state = awaitItem()
            }

            assertThat(state.entries.single().prompt).isEqualTo("a red fox")

            viewModel.clearFilters()
            advanceUntilIdle()
            state = awaitItem()
            while (state.entries.size != 2) {
                advanceUntilIdle()
                state = awaitItem()
            }

            assertThat(state.entries).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `toggling favourites only filters properly`() = runTest(dispatcher) {
        val id1 = repository.record(entry(made("1.png"), isFavourite = false)).id
        repository.record(entry(made("2.png"), isFavourite = true))
        advanceUntilIdle()

        val viewModel = GalleryViewModel(repository, SavedStateHandle())

        viewModel.uiState.test {
            advanceUntilIdle()
            var state = awaitItem()
            while (state.isLoading || state.total < 2) {
                advanceUntilIdle()
                state = awaitItem()
            }

            viewModel.toggleFavouritesOnly()
            advanceUntilIdle()
            state = awaitItem()
            while (state.entries.size != 1) {
                advanceUntilIdle()
                state = awaitItem()
            }

            assertThat(state.entries.single().isFavourite).isTrue()

            viewModel.setFavourite(id1, true)
            advanceUntilIdle()
            state = awaitItem()
            while (state.entries.size != 2) {
                advanceUntilIdle()
                state = awaitItem()
            }

            assertThat(state.entries).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `deleting an entry removes it from the list`() = runTest(dispatcher) {
        val id = repository.record(entry(made("del.png"))).id
        advanceUntilIdle()

        val viewModel = GalleryViewModel(repository, SavedStateHandle())

        viewModel.uiState.test {
            advanceUntilIdle()
            var state = awaitItem()
            while (state.isLoading || state.total < 1) {
                advanceUntilIdle()
                state = awaitItem()
            }

            assertThat(state.entries).hasSize(1)

            viewModel.delete(id)
            advanceUntilIdle()
            state = awaitItem()
            while (state.entries.isNotEmpty()) {
                advanceUntilIdle()
                state = awaitItem()
            }

            assertThat(state.entries).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `saved state is restored across recreation`() = runTest(dispatcher) {
        val savedState = SavedStateHandle(
            mapOf(
                "gallery.sort" to GallerySort.FASTEST.name,
                "gallery.modalities" to arrayOf(GenerationTask.SPEECH.name),
                "gallery.favourites" to true,
                "gallery.search" to "spoken",
            ),
        )

        val viewModel = GalleryViewModel(repository, savedState)

        viewModel.uiState.test {
            advanceUntilIdle()
            val state = awaitItem()
            assertThat(state.query.sort).isEqualTo(GallerySort.FASTEST)
            assertThat(state.query.modalities).containsExactly(GenerationTask.SPEECH)
            assertThat(state.query.favouritesOnly).isTrue()
            assertThat(state.query.search).isEqualTo("spoken")
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }
}

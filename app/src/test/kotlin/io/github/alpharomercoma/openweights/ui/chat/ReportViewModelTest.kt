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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.data.Clock
import io.github.alpharomercoma.openweights.core.data.ContentReportRepository
import io.github.alpharomercoma.openweights.core.data.ReportReason
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
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

/**
 * In-app reporting of model output.
 *
 * Play requires this of anything that generates AI content. Nothing is transmitted, so the
 * only evidence a report happened is the row it writes, which is what these check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: OpenWeightsDatabase
    private lateinit var viewModel: ReportViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Room's own executor is a real background thread, so advanceUntilIdle returns
        // before a write has landed and the assertions race it. Binding both executors to
        // the test dispatcher puts the database on the same scheduler as everything else.
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenWeightsDatabase::class.java,
        )
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .allowMainThreadQueries()
            .build()
        viewModel = ReportViewModel(ContentReportRepository(database, Clock.System))
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a report records the model, the reason, the reply and the note`() = runTest(dispatcher) {
        viewModel.report(
            modelName = "lfm2.5-2.6b",
            replyText = "something it should not have said",
            reason = ReportReason.OFFENSIVE,
            note = "not acceptable",
        )
        advanceUntilIdle()

        val stored = database.reports().observeAll().first().single()
        assertThat(stored.modelName).isEqualTo("lfm2.5-2.6b")
        assertThat(stored.reason).isEqualTo("offensive")
        assertThat(stored.replyText).isEqualTo("something it should not have said")
        assertThat(stored.note).isEqualTo("not acceptable")
        assertThat(viewModel.confirmation.value).isNotNull()
    }

    @Test
    fun `a report with no model names nothing that could be acted on, so none is filed`() =
        runTest(dispatcher) {
            viewModel.report(null, "orphaned", ReportReason.WRONG, "")
            viewModel.report("   ", "orphaned", ReportReason.WRONG, "")
            advanceUntilIdle()

            assertThat(database.reports().observeAll().first()).isEmpty()
            assertThat(viewModel.confirmation.value).isNull()
        }

    @Test
    fun `reports accumulate per model, which is the only quality signal this app has`() =
        runTest(dispatcher) {
            viewModel.report("noisy-model", "one", ReportReason.OFFENSIVE, "")
            advanceUntilIdle()
            viewModel.report("noisy-model", "two", ReportReason.DANGEROUS, "")
            advanceUntilIdle()
            viewModel.report("quiet-model", "three", ReportReason.WRONG, "")
            advanceUntilIdle()

            val repository = ContentReportRepository(database, Clock.System)
            assertThat(repository.countFor("noisy-model")).isEqualTo(2)
            assertThat(repository.countFor("quiet-model")).isEqualTo(1)
            assertThat(repository.countFor("never-reported")).isEqualTo(0)
        }
}

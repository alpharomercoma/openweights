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

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a settings file written by an older build means now.
 *
 * The whole of the context window change turns on this: the sentinel only reaches somebody
 * whose stored value is not a number, and every install that predates it has 4096 written
 * against every model it has ever opened.
 */
@RunWith(RobolectricTestRunner::class)
class ModelPreferencesTest {
    private val repository =
        ModelPreferencesRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `the window the app used to default to is read as never having been chosen`() = runTest {
        // Written the way an older build wrote it: no version stamp on the file.
        repository.saveRaw("old.gguf", """{"contextLength":4096}""")

        assertThat(repository.current("old.gguf").contextLength)
            .isEqualTo(ModelPreferences.AUTOMATIC)
    }

    @Test
    fun `a window somebody actually moved the slider to is left alone`() = runTest {
        repository.save("pinned.gguf", ModelPreferences(contextLength = 8_192))

        assertThat(repository.current("pinned.gguf").contextLength).isEqualTo(8_192)
    }

    @Test
    fun `a model nobody has opened is automatic from the start`() = runTest {
        assertThat(repository.current("unseen.gguf").contextLength)
            .isEqualTo(ModelPreferences.AUTOMATIC)
    }

    @Test
    fun `4096 chosen on purpose stays chosen, however many times it is read`() = runTest {
        // The migration used to have no way to stop being true. Anyone who dragged the slider
        // to exactly the old default had it turned back to automatic on the next load, and
        // the one after that, forever.
        repository.save("deliberate.gguf", ModelPreferences(contextLength = 4_096))

        repeat(3) {
            assertThat(repository.current("deliberate.gguf").contextLength).isEqualTo(4_096)
        }
    }

    @Test
    fun `everything else in an old settings file survives the migration`() = runTest {
        repository.saveRaw(
            "old.gguf",
            """{"contextLength":4096,"temperature":0.2,"systemPrompt":"Be terse"}""",
        )

        val read = repository.current("old.gguf")

        assertThat(read.temperature).isEqualTo(0.2f)
        assertThat(read.systemPrompt).isEqualTo("Be terse")
    }
}

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

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.context.CompactionPolicy
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.engine.StopReason
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What may be stored as a summary, and what may not.
 *
 * A fold is the one operation here that destroys information on purpose: the turns it covers
 * are dropped from every future prompt and only the summary speaks for them. So the summary
 * has to be whole, and "whole" is a fact the engine reports rather than something that can be
 * read off the text.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationCompactorTest {
    private val engine = FakeInferenceEngine()
    private val compactor = ConversationCompactor(engine, CompactionPolicy())

    private fun conversation(turns: Int): ChatUiState = ChatUiState(
        contextSize = 4_096,
        transcript = (0 until turns).map { index ->
            TranscriptEntry(
                id = index.toLong(),
                role = if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
                text = "Turn $index, with enough words in it to be worth folding away later.",
            )
        },
    )

    private suspend fun load() {
        val file = File.createTempFile("compactor", ".gguf")
        engine.load(file, ModelLoadParams(contextLength = 4_096))
    }

    @Test
    fun `a summary that finished is kept`() = runBlocking {
        load()
        engine.scripted += ScriptedPass("They discussed folding conversations.")

        val folded = compactor.compact(conversation(20), engineIsDecoding = false)

        assertThat(folded).isNotNull()
        assertThat(folded?.summary).contains("folding conversations")
    }

    @Test
    fun `a summary cut off by its own budget is refused rather than stored`() = runBlocking {
        load()
        // Plausible, and half of a sentence. This is the shape the file's own measurements
        // produced at a smaller budget, and nothing about the text says it is incomplete:
        // without the stop reason there is no way to tell it from a summary that finished.
        engine.scripted += ScriptedPass(
            "They discussed folding conversations, and then the user asked about",
            reason = StopReason.MAX_TOKENS,
        )

        val folded = compactor.compact(conversation(20), engineIsDecoding = false)

        // Not folded, so the real turns stay in the conversation. Worse for the window and
        // honest about what is in it; half a summary would silently delete whatever it had
        // not reached.
        assertThat(folded).isNull()
    }

    @Test
    fun `a summary the window ran out on is refused too`() = runBlocking {
        load()
        engine.scripted += ScriptedPass(
            "They discussed folding conversations and",
            reason = StopReason.CONTEXT_FULL,
        )

        assertThat(compactor.compact(conversation(20), engineIsDecoding = false)).isNull()
    }

    companion object {
        init {
            // Robolectric needs an application before the engine touches one.
            ApplicationProvider.getApplicationContext<android.app.Application>()
        }
    }
}

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

package io.github.alpharomercoma.openweights.core.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.assistantHistoryText
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rolling a sliding-window model back further than its window remembers.
 *
 * Gemma 3 attends over the whole conversation on one layer in six and over the last 512
 * positions on the other five. A regenerate or an edit rolls the cache back to the end of
 * the shared prefix, and a rollback longer than the window is only safe if the local
 * layers still hold the positions just before that point. Whether they do is a context
 * setting: llama.cpp's default context keeps those layers' cache full (`swa_full`), and
 * this engine takes the default, so the rollback is honoured and reused. A build that
 * switched to the windowed cache to save memory would find those positions evicted, and
 * `Session::align_cache` refuses the rollback in that case, as llama-server does.
 *
 * Measured on a Snapdragon 8 Gen 3 on 2026-09-02, both shapes over one model, judged by
 * the engine's own prompt accounting:
 *
 *  1. a rollback shorter than the window, reused
 *  2. a rollback longer than the window, reused too under the shipped setting, and paid
 *     for in full the day the setting changes; either way the reply must still read
 *
 * Push the model to `bench/` rather than beside `model.gguf`: the other engine tests take
 * the first `.gguf` they find there, and `gemma.gguf` sorts ahead of it.
 * ```
 * adb push gemma-3-1b-it-Q4_K_M.gguf /data/local/tmp/openweights/bench/gemma.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SlidingWindowOnDeviceTest {

    @Test
    fun aRollbackWithinTheWindowIsStillReused() = runBlocking {
        assumeTrue("no sliding-window model at ${MODEL.path}", MODEL.isFile)

        LlamaCppEngine().use { engine ->
            engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))
            val first = listOf(ChatMessage.text(ChatRole.USER, longQuestion("first")))
            val opening = engine.turn(first)
            Log.i(TAG, "turn 1: ${describe(opening)}")
            assertThat(opening.stats.promptTokens).isGreaterThan(WINDOW)

            val history = first +
                ChatMessage.text(ChatRole.ASSISTANT, assistantHistoryText(opening.content))
            val second = engine.turn(history + ChatMessage.text(ChatRole.USER, "And in one word?"))
            Log.i(TAG, "turn 2: ${describe(second)}")

            // The same follow-up asked differently: a rollback over one short question
            // and one short reply, well inside the window, so the cache must hold.
            val regenerated =
                engine.turn(history + ChatMessage.text(ChatRole.USER, "Say it in two words."))
            Log.i(TAG, "turn 3, short rollback: ${describe(regenerated)}")

            assertThat(regenerated.stats.promptTokens).isLessThan(opening.stats.promptTokens / 4)
            assertThat(regenerated.content.trim()).isNotEmpty()
        }
    }

    @Test
    fun aRollbackPastTheWindowStillReads() = runBlocking {
        assumeTrue("no sliding-window model at ${MODEL.path}", MODEL.isFile)

        LlamaCppEngine().use { engine ->
            engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))
            val first = listOf(ChatMessage.text(ChatRole.USER, longQuestion("first")))
            val opening = engine.turn(first)
            Log.i(TAG, "turn 1: ${describe(opening)}")
            assertThat(opening.stats.promptTokens).isGreaterThan(WINDOW)

            val history = first +
                ChatMessage.text(ChatRole.ASSISTANT, assistantHistoryText(opening.content))
            // A second question longer than the window, then the same slot rewritten:
            // the rollback runs over the whole of it, past what the local layers kept.
            val second =
                engine.turn(history + ChatMessage.text(ChatRole.USER, longQuestion("second")))
            Log.i(TAG, "turn 2: ${describe(second)}")
            assertThat(second.stats.promptTokens).isGreaterThan(WINDOW)

            val rewritten =
                engine.turn(history + ChatMessage.text(ChatRole.USER, longQuestion("third")))
            Log.i(TAG, "turn 3, rollback past the window: ${describe(rewritten)}")

            // With the full cache on the window layers the rollback is reused, so this
            // turn pays for the new question and not for the first turn again: about what
            // turn 2 paid, well under a full re-read. Measured here at 1,151 tokens against
            // a full prompt of about 2,300.
            assertThat(rewritten.stats.promptTokens)
                .isLessThan(second.stats.promptTokens + opening.stats.promptTokens / 2)
            // And the reply is still a reply, not what a window with a hole in it makes.
            assertThat(rewritten.content.trim()).isNotEmpty()
            assertThat(rewritten.content).doesNotContain("<start_of_turn>")
        }
    }

    private suspend fun InferenceEngine.turn(messages: List<ChatMessage>) = chat(
        messages = messages,
        params = SamplerParams(temperature = 0f, maxTokens = ANSWER_TOKENS, seed = 1),
    ).toList().filterIsInstance<GenerationEvent.Completed>().single()

    private fun describe(event: GenerationEvent.Completed): String =
        "prompt=%d tokens prefill=%dms generated=%d context=%d reply=%s".format(
            event.stats.promptTokens,
            event.stats.prefillMs,
            event.stats.generatedTokens,
            event.stats.contextUsed,
            event.content.take(REPLY_PREVIEW).replace('\n', ' '),
        )

    /**
     * A question longer than the window, distinct per [label] so no two of them share a
     * prefix past their first words: the point is where the shared prefix ends.
     */
    private fun longQuestion(label: String): String = buildString {
        append("This is the ").append(label).append(" version of my notes. ")
        repeat(STANZAS) { index ->
            append("Point ").append(index + 1).append(" of the ").append(label)
            append(" notes: the app runs language models on the phone itself, keeps ")
            append("every conversation on the device, and reads the web only when a ")
            append("tool is asked to. ")
        }
        append("Summarise the ").append(label).append(" notes in one sentence.")
    }

    private companion object {
        const val TAG = "OpenWeights"
        val MODEL = File("/data/local/tmp/openweights/bench/gemma.gguf")
        const val CONTEXT = 4096

        /** Gemma 3's local-attention window, in positions. */
        const val WINDOW = 512
        const val ANSWER_TOKENS = 48
        const val REPLY_PREVIEW = 80

        /** About 28 tokens a stanza, so thirty of them clear the window comfortably. */
        const val STANZAS = 30
    }
}

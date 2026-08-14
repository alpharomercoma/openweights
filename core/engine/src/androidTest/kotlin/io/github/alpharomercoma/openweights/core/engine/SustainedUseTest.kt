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
import com.google.common.truth.Truth.assertWithMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Many turns on one loaded model, which is where the cache used to quietly fill with tokens
 * nobody could see.
 *
 * A transformer's KV cache is a row per token and can be cut anywhere. A recurrent or hybrid
 * model carries a running state instead, so it can only roll back as far as it kept snapshots
 * for, which by default is not at all. It says so by returning false from `seq_rm`, and the
 * engine used to ignore that and rewind its own bookkeeping regardless. Nothing was removed,
 * the next batch went in after the tail that should have gone, and the cache grew by a whole
 * prompt every turn until `llama_decode returned 1` arrived with no slot left. On LFM2 that
 * happened on **every follow-up turn**, because `n_past_` counts the tokens the model
 * generated and `cached_` does not, so the rollback path is taken whenever anything was said.
 *
 * The shape of the test follows from the shape of the bug. Each turn sends a long question
 * that shares only a short prefix with the last one, so the cache is asked to roll back a
 * long way and the leak is fast: with the fault present this reaches the ceiling in about a
 * dozen turns, and without it the occupancy is flat because each turn replaces the last.
 *
 * Point it at a hybrid model or it proves nothing:
 * ```
 * adb push LFM2-1.2B-Q4_K_M.gguf /data/local/tmp/openweights/bench/lfm.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SustainedUseTest {
    private lateinit var engine: InferenceEngine

    @Before
    fun setUp() {
        assumeTrue("no hybrid model at ${MODEL.path}", MODEL.isFile)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.close() }
    }

    @Test
    fun aLongRunOfTurnsDoesNotFillTheCacheWithTokensNobodyCanSee() = runBlocking<Unit> {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))

        val used = mutableListOf<Int>()
        repeat(TURNS) { turn ->
            // Deliberately not reset between turns: resetting is what hides this, and it is
            // not what the app does between one question and the next.
            val completed = engine.chat(
                messages = listOf(
                    ChatMessage.text(ChatRole.SYSTEM, "Answer in one short sentence."),
                    ChatMessage.text(ChatRole.USER, question(turn)),
                ),
                params = SamplerParams(temperature = 0f, maxTokens = BUDGET, seed = 1),
            ).toList().filterIsInstance<GenerationEvent.Completed>().single()

            used += completed.stats.contextUsed
            assertWithMessage("turn $turn of $TURNS ended as ${completed.reason}")
                .that(completed.reason)
                .isNoneOf(StopReason.ERROR, StopReason.CONTEXT_FULL)
        }

        Log.i(TAG, "CONTEXT used=$used")
        // Flat, not climbing. Every turn replaces the one before it, so the last turn should
        // cost about what the first one did. Twice over is generous and still far from the
        // unbounded growth the fault produced.
        assertWithMessage("context used climbed across turns: $used")
            .that(used.last())
            .isLessThan(used.first() * 2)
    }

    /** Long, and different every turn past a short shared opening, so the rollback is deep. */
    private fun question(turn: Int): String =
        "Here are some notes. " + "Item $turn is worth remembering. ".repeat(REPEATS) +
            "What number were the notes about?"

    private companion object {
        const val TAG = "OpenWeightsSustained"
        const val CONTEXT = 4096

        /** Short replies: what is being measured is the prompt side. */
        const val BUDGET = 16

        /**
         * Enough to pass the window several times over if nothing is ever reclaimed.
         *
         * Each turn appends roughly three hundred tokens under the fault, so twelve would
         * reach the ceiling and this leaves room to be sure.
         */
        const val TURNS = 20

        /** About three hundred tokens of question, so the leak is one turn per twelfth. */
        const val REPEATS = 40

        val MODEL = File("/data/local/tmp/openweights/bench/lfm.gguf")
    }
}

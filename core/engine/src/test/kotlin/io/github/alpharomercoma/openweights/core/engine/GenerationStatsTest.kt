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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GenerationStatsTest {
    @Test
    fun `decode throughput excludes the first token`() {
        // The first token's cost is time-to-first-token, which is dominated by prefill.
        // Counting it as decode work would understate how fast generation actually ran.
        val stats = stats(generatedTokens = 11, decodeMs = 1000)

        assertThat(stats.decodeTokensPerSecond).isEqualTo(10.0)
    }

    @Test
    fun `decode throughput is unknown for a single token`() {
        val stats = stats(generatedTokens = 1, decodeMs = 500)

        assertThat(stats.decodeTokensPerSecond).isNull()
    }

    @Test
    fun `prefill throughput is unknown when the whole prompt was cached`() {
        // A fully cached prompt decodes nothing, so there is no rate to report: showing
        // "0 tok/s" would read as "the device is slow" rather than "there was no work".
        val stats = stats(promptTokens = 0, prefillMs = 0)

        assertThat(stats.prefillTokensPerSecond).isNull()
    }

    @Test
    fun `prefill throughput counts every decoded prompt token`() {
        val stats = stats(promptTokens = 64, prefillMs = 1000)

        assertThat(stats.prefillTokensPerSecond).isEqualTo(64.0)
    }

    @Test
    fun `cache hit rate is null with no prompt at all`() {
        // The degenerate case the divide has to guard against, not one anything actually
        // sends: every real turn has at least the rendered chat template in it.
        val stats = stats(promptTokens = 0, cachedTokens = 0)

        assertThat(stats.cacheHitRate).isNull()
    }

    @Test
    fun `cache hit rate is the reused share of this turn's full prompt`() {
        // A follow-up turn that reused 900 of the 1000 tokens the conversation now
        // tokenizes to only paid for the 100 that changed.
        val stats = stats(promptTokens = 100, cachedTokens = 900)

        assertThat(stats.totalPromptTokens).isEqualTo(1000)
        assertThat(stats.cacheHitRate).isEqualTo(0.9)
    }

    @Test
    fun `a conversation's first turn is a real, honest zero, not null`() {
        // There is nothing yet in the cache to match against, and that is exactly what a
        // full miss is — a genuinely different claim from the no-prompt-at-all case above,
        // which is why that one is null and this one is a real 0.0.
        val stats = stats(promptTokens = 400, cachedTokens = 0, generatedTokens = 2)

        assertThat(stats.cacheHitRate).isEqualTo(0.0)
    }

    @Test
    fun `a turn of several passes reports what the passes cost together`() {
        // A tool turn is two generations: a first pass that read the conversation and asked
        // for the tool, and a closing pass that read the result and answered. The reply's
        // row used to keep only the closing pass, which on a real device reported a 3,000
        // token re-read as a few hundred tokens of follow-up.
        val opening = stats(
            promptTokens = 3000,
            generatedTokens = 20,
            prefillMs = 30_000,
            decodeMs = 1_000,
            cachedTokens = 0,
        )
        val closing = stats(
            promptTokens = 300,
            generatedTokens = 101,
            prefillMs = 3_000,
            decodeMs = 4_000,
            cachedTokens = 3020,
        )

        val turn = opening.through(closing)

        assertThat(turn.promptTokens).isEqualTo(3300)
        assertThat(turn.cachedTokens).isEqualTo(3020)
        assertThat(turn.generatedTokens).isEqualTo(121)
        // The rates come out of the summed fields, so they describe the whole turn.
        assertThat(turn.prefillTokensPerSecond).isEqualTo(3300 * 1000.0 / 33_000)
        assertThat(turn.decodeTokensPerSecond).isEqualTo(120 * 1000.0 / 5_000)
        // Where the context stands is the closing pass's to say.
        assertThat(turn.contextUsed).isEqualTo(closing.contextUsed)
    }

    private fun stats(
        promptTokens: Int = 0,
        generatedTokens: Int = 0,
        prefillMs: Long = 0,
        decodeMs: Long = 0,
        cachedTokens: Int = 0,
    ) = GenerationStats(
        promptTokens = promptTokens,
        generatedTokens = generatedTokens,
        prefillMs = prefillMs,
        decodeMs = decodeMs,
        timeToFirstTokenMs = 0,
        contextUsed = 0,
        contextSize = 2048,
        cachedTokens = cachedTokens,
    )
}

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

    private fun stats(
        promptTokens: Int = 0,
        generatedTokens: Int = 0,
        prefillMs: Long = 0,
        decodeMs: Long = 0,
    ) = GenerationStats(
        promptTokens = promptTokens,
        generatedTokens = generatedTokens,
        prefillMs = prefillMs,
        decodeMs = decodeMs,
        timeToFirstTokenMs = 0,
        contextUsed = 0,
        contextSize = 2048,
    )
}

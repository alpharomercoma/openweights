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

package io.github.alpharomercoma.openweights.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TelemetryTest {
    @Test
    fun `formatTokenCount below a thousand is exact`() {
        assertThat(formatTokenCount(0)).isEqualTo("0")
        assertThat(formatTokenCount(847)).isEqualTo("847")
    }

    @Test
    fun `formatTokenCount above a thousand keeps one decimal`() {
        assertThat(formatTokenCount(12_400)).isEqualTo("12.4k")
        assertThat(formatTokenCount(1_000)).isEqualTo("1.0k")
    }

    @Test
    fun `formatTokenCount above a million switches suffix`() {
        assertThat(formatTokenCount(1_500_000)).isEqualTo("1.5M")
    }

    @Test
    fun `session status is null with no conversation yet`() {
        // A conversation with no replies has nothing to report, and showing zeroes would
        // read as a real, measured "nothing happened" rather than "nothing to measure yet".
        assertThat(sessionStatusText(inputTokens = null, outputTokens = null, cacheHitRate = null))
            .isNull()
    }

    @Test
    fun `session status shows tokens without a cache figure when none was reported`() {
        assertThat(sessionStatusText(inputTokens = 1_840, outputTokens = 512, cacheHitRate = null))
            .isEqualTo("↑1.8k ↓512")
    }

    @Test
    fun `session status appends cache hit rate when known`() {
        assertThat(sessionStatusText(inputTokens = 1_840, outputTokens = 512, cacheHitRate = 0.92))
            .isEqualTo("↑1.8k ↓512 · CH92%")
    }

    @Test
    fun `session status reports a real zero cache hit rate, not silence`() {
        // A conversation's first turn is a genuine 0%, not a missing measurement — see
        // GenerationStats.cacheHitRate. Silently dropping the segment here would make a
        // truthful zero look identical to "not tracked yet".
        assertThat(sessionStatusText(inputTokens = 400, outputTokens = 128, cacheHitRate = 0.0))
            .isEqualTo("↑400 ↓128 · CH0%")
    }
}

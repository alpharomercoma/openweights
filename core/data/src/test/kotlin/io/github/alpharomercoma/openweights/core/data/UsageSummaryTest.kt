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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageSummaryTest {
    @Test
    fun `average speed is total tokens over total time, not a mean of means`() {
        // A slow 1000-token reply and a fast 10-token one must not average to "fast".
        // Weighting by work done is the only figure that describes the device honestly.
        val summary = UsageSummary(
            lifetimeGeneratedTokens = 1010,
            lifetimeInferenceMs = 101_000,
        )

        assertThat(summary.averageTokensPerSecond).isWithin(0.01).of(10.0)
    }

    @Test
    fun `speed is unknown before anything has been generated`() {
        assertThat(UsageSummary().averageTokensPerSecond).isNull()
    }

    @Test
    fun `a day with no inference time does not report an infinite rate`() {
        val summary = UsageSummary(lifetimeGeneratedTokens = 500, lifetimeInferenceMs = 0)

        assertThat(summary.averageTokensPerSecond).isNull()
    }
}

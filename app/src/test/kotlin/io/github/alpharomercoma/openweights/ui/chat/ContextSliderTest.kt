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

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Every stop on the context slider is a whole number of tokens.
 *
 * The reported symptom was a slider that bounces between two positions instead of settling.
 * The cause is arithmetic rather than gesture handling: the value is stored as an `Int`, so
 * a stop that falls on 4,195.1 is stored as 4,195, handed back, snapped again, and the thumb
 * never lands. Whether it happens depends entirely on whether the range divides evenly by
 * the number of intervals, which is why it was invisible at the 32,768 default and appeared
 * on a model reporting 131,072.
 *
 * Checked over the context sizes models actually declare rather than a couple of examples,
 * because the failure is a property of the number and picking friendly numbers is how it got
 * missed the first time.
 */
class ContextSliderTest {
    @Test
    fun `every stop is a whole number of tokens, for every context a model might report`() {
        val reported = listOf(
            2_048, 4_096, 8_192, 16_384, 32_768, 32_769, 40_000, 65_536, 100_000,
            128_000, 131_072, 200_000, 262_144, 1_000_000,
        )

        reported.forEach { context ->
            val range = contextRange(context)
            val intervals = ModelLoadParams.CONTEXT_STEPS + 1
            val stride = (range.endInclusive - range.start) / intervals

            assertThat(stride).isEqualTo(stride.roundToInt().toFloat())

            // The property that actually matters: rounding a stop leaves it on that stop, so
            // handing it back does not move the thumb.
            repeat(intervals + 1) { step ->
                val stop = range.start + stride * step
                assertThat(stop.roundToInt().toFloat()).isEqualTo(stop)
            }
        }
    }

    @Test
    fun `the top is never trimmed by more than one stop`() {
        listOf(32_768, 131_072, 262_144, 1_000_000).forEach { context ->
            val top = contextRange(context).endInclusive.toInt()
            assertThat(top).isAtMost(maxOf(context, ModelLoadParams.MAX_CONTEXT_LENGTH))
            assertThat(maxOf(context, ModelLoadParams.MAX_CONTEXT_LENGTH) - top)
                .isLessThan(ModelLoadParams.CONTEXT_STEPS + 1)
        }
    }
}

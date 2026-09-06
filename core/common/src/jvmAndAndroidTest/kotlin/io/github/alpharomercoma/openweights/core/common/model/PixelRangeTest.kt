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

package io.github.alpharomercoma.openweights.core.common.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PixelRangeTest {
    @Test
    fun `each range maps the byte extremes where the encoder expects them`() {
        assertThat(
            PixelRange.RAW.applyTo(floatArrayOf(0f, 255f)).toList(),
        ).containsExactly(0f, 255f).inOrder()
        assertThat(
            PixelRange.UNIT.applyTo(floatArrayOf(0f, 255f)).toList(),
        ).containsExactly(0f, 1f).inOrder()
        assertThat(
            PixelRange.SIGNED.applyTo(floatArrayOf(0f, 255f)).toList(),
        ).containsExactly(-1f, 1f).inOrder()
        // Mid grey sits at the centre of the signed range, which is what mean 0.5 means.
        assertThat(PixelRange.SIGNED.applyTo(floatArrayOf(127.5f))[0]).isWithin(1e-6f).of(0f)
    }
}

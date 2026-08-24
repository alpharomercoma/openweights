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
import org.junit.Assert.assertThrows
import org.junit.Test

class SamplerParamsTest {
    @Test
    fun `rejects values the native sampler cannot use`() {
        // These reach llama.cpp through JNI, where a bad value is undefined behaviour
        // rather than an exception. Reject them at the boundary instead.
        assertThrows(IllegalArgumentException::class.java) { SamplerParams(temperature = -1f) }
        assertThrows(IllegalArgumentException::class.java) { SamplerParams(topP = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { SamplerParams(minP = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { SamplerParams(repeatPenalty = 0f) }
        assertThrows(IllegalArgumentException::class.java) { SamplerParams(maxTokens = -1) }
    }

    @Test
    fun `allows greedy decoding`() {
        // Temperature 0 is a valid request for deterministic output, not a bad value.
        assertThat(SamplerParams(temperature = 0f).temperature).isEqualTo(0f)
    }

    @Test
    fun `rejects load parameters that cannot produce a context`() {
        assertThrows(IllegalArgumentException::class.java) { ModelLoadParams(contextLength = 0) }
        assertThrows(IllegalArgumentException::class.java) { ModelLoadParams(threadCount = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            ModelLoadParams(batchThreadCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) { ModelLoadParams(gpuLayers = -1) }
    }

    @Test
    fun `thread counts default to the engine's choice`() {
        val params = ModelLoadParams()

        assertThat(params.threadCount).isNull()
        assertThat(params.batchThreadCount).isNull()
    }
}

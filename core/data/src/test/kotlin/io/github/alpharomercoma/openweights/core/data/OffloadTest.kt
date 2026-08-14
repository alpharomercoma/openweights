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

/**
 * Which processor a model loads onto, and why.
 *
 * The rule is a measurement rather than a preference, so these are the measurement written
 * down: the shapes of turn that were timed on an Adreno 830, and the answer each one wants.
 */
class OffloadTest {
    @Test
    fun `a device with no gpu is always on the cpu`() {
        Offload.entries.forEach { choice ->
            assertThat(choice.layersFor(hasGpu = false, promptTokens = 9_000, generatedTokens = 1))
                .isEqualTo(0)
        }
    }

    @Test
    fun `asking for one processor gets that processor whatever the history says`() {
        // Chat-shaped history, which auto would answer with the CPU.
        assertThat(Offload.GPU.layersFor(hasGpu = true, promptTokens = 16, generatedTokens = 300))
            .isGreaterThan(0)
        // Agent-shaped history, which auto would answer with the GPU.
        assertThat(Offload.CPU.layersFor(hasGpu = true, promptTokens = 2_017, generatedTokens = 46))
            .isEqualTo(0)
    }

    @Test
    fun `auto puts a model that has only ever chatted on the cpu`() {
        // The chat turn as measured: sixteen tokens in, three hundred out. The CPU finished
        // it in 6.4 seconds and the GPU took 10.2.
        assertThat(Offload.AUTO.layersFor(hasGpu = true, promptTokens = 16, generatedTokens = 300))
            .isEqualTo(0)
    }

    @Test
    fun `auto puts a model that reads more than it writes on the gpu`() {
        // The agent turn as measured: two thousand tokens of conversation and search
        // results in, forty-six out. The GPU finished it in 4.6 seconds and the CPU 14.5.
        assertThat(
            Offload.AUTO.layersFor(hasGpu = true, promptTokens = 2_017, generatedTokens = 46),
        ).isGreaterThan(0)
    }

    @Test
    fun `auto stays on the cpu until there is something to go on`() {
        // A fresh install has nothing recorded. Being slower to write is felt on the first
        // reply; being slower to read is not felt at all until a conversation is long.
        assertThat(Offload.AUTO.layersFor(hasGpu = true, promptTokens = 0, generatedTokens = 0))
            .isEqualTo(0)
    }

    @Test
    fun `the crossover sits where the two rates say it should`() {
        // Solving 151 and 45 against 624 and 34 puts it at a prompt about 1.4 times the
        // answer. Either side of that by a hair, so the constant cannot drift unnoticed.
        assertThat(Offload.AUTO.layersFor(hasGpu = true, promptTokens = 139, generatedTokens = 100))
            .isEqualTo(0)
        assertThat(Offload.AUTO.layersFor(hasGpu = true, promptTokens = 141, generatedTokens = 100))
            .isGreaterThan(0)
    }

    @Test
    fun `an offload written by a newer build falls back to auto`() {
        assertThat(Offload.fromName("NPU")).isEqualTo(Offload.AUTO)
        assertThat(ModelPreferences().offload).isEqualTo(Offload.AUTO.name)
    }
}

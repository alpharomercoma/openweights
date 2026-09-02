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
 * Choosing a processor for each half of a turn, and what that actually sets.
 *
 * There are only two knobs underneath: how many layers live on the GPU, which serves both
 * halves, and `op_offload`, which reaches only batches large enough to repay the transfer
 * and therefore only prompt reading. These tests exist so that a control cannot quietly
 * stop moving either one.
 */
class ComputeTargetTest {

    @Test
    fun `asking for the GPU on either half actually reaches the GPU`() {
        // The correction that matters. llama.cpp can separate the halves with op_offload —
        // it hands over only batches large enough to repay the transfer, and generation is
        // always a batch of one — but ggml-opencl leaves .offload_op null, and
        // ggml_backend_dev_offload_op returns false for a backend that does not implement
        // it. OpenCL is the GPU backend compiled in here, so with no layers resident the
        // scheduler moves nothing and both halves run on the CPU.
        //
        // An earlier version of this returned zero layers for exactly this combination,
        // which made "read on the GPU" a label rather than a setting.
        val readOnGpu = computeLayersFor(
            prefill = ComputeTarget.GPU,
            decode = ComputeTarget.CPU,
            hasGpu = true,
            promptTokens = 0,
            generatedTokens = 0,
        )

        assertThat(readOnGpu).isGreaterThan(0)
        // The flag is still set, and still correct: it starts separating the halves on its
        // own the moment a backend that implements offload_op is enabled.
        assertThat(preferences(ComputeTarget.GPU, ComputeTarget.CPU).toLoadParams().opOffload)
            .isTrue()
    }

    @Test
    fun `writing on the GPU puts the weights there, which covers reading too`() {
        val layers = computeLayersFor(
            prefill = ComputeTarget.AUTO,
            decode = ComputeTarget.GPU,
            hasGpu = true,
            promptTokens = 0,
            generatedTokens = 0,
        )

        assertThat(layers).isGreaterThan(0)
    }

    @Test
    fun `pinning reading to the CPU turns the offload off`() {
        val params = preferences(ComputeTarget.CPU, ComputeTarget.AUTO).toLoadParams()

        // Otherwise a prompt still leaves for the GPU, and "CPU" would be a label rather
        // than a setting.
        assertThat(params.opOffload).isFalse()
    }

    @Test
    fun `reading pinned to the CPU keeps the weights local even with writing left open`() {
        val layers = computeLayersFor(
            prefill = ComputeTarget.CPU,
            decode = ComputeTarget.AUTO,
            hasGpu = true,
            promptTokens = 100_000,
            generatedTokens = 1,
        )

        // The mirror of the case below. This one fell through to the heuristic, which put
        // every layer on the GPU for a long prompt and read there against the pin.
        assertThat(layers).isEqualTo(0)
    }

    @Test
    fun `both halves pinned to the CPU keep the weights and the batches local`() {
        val layers = computeLayersFor(
            prefill = ComputeTarget.CPU,
            decode = ComputeTarget.CPU,
            hasGpu = true,
            promptTokens = 100_000,
            generatedTokens = 1,
        )

        // Prompt-heavy history, and still nothing on the GPU: pinning both halves has to
        // beat the heuristic or it is not a setting.
        assertThat(layers).isEqualTo(0)
        assertThat(preferences(ComputeTarget.CPU, ComputeTarget.CPU).toLoadParams().opOffload)
            .isFalse()
    }

    @Test
    fun `a device with no GPU keeps everything local whatever is asked for`() {
        val layers = computeLayersFor(
            prefill = ComputeTarget.GPU,
            decode = ComputeTarget.GPU,
            hasGpu = false,
            promptTokens = 10_000,
            generatedTokens = 1,
        )

        assertThat(layers).isEqualTo(0)
    }

    @Test
    fun `auto on both halves still follows the measured crossover`() {
        // A prompt-heavy history moves to the GPU; an answer-heavy one does not. This is
        // the behaviour the single Offload control had, and it must survive the split.
        val promptHeavy = computeLayersFor(
            ComputeTarget.AUTO,
            ComputeTarget.AUTO,
            hasGpu = true,
            promptTokens = 100_000,
            generatedTokens = 1,
        )
        val answerHeavy = computeLayersFor(
            ComputeTarget.AUTO,
            ComputeTarget.AUTO,
            hasGpu = true,
            promptTokens = 1,
            generatedTokens = 100_000,
        )

        assertThat(promptHeavy).isGreaterThan(0)
        assertThat(answerHeavy).isEqualTo(0)
    }

    @Test
    fun `defaults to auto on both halves`() {
        val fresh = ModelPreferences()

        assertThat(fresh.prefillTarget).isEqualTo(ComputeTarget.AUTO)
        assertThat(fresh.decodeTarget).isEqualTo(ComputeTarget.AUTO)
        // Matching llama.cpp, and only reachable at all where there is a GPU to offload to.
        assertThat(fresh.toLoadParams().opOffload).isTrue()
    }

    private fun preferences(prefill: ComputeTarget, decode: ComputeTarget) =
        ModelPreferences(prefillTarget = prefill, decodeTarget = decode)
}

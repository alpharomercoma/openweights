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
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The GPU path, on the devices that have one.
 *
 * Skipped where no GPU backend registered, which is most phones: only Qualcomm ships an
 * OpenCL driver in any quantity. Where one does exist this is the only test that proves
 * offloading works rather than merely compiling, because a backend that fails to attach
 * falls back to the CPU silently and everything else still passes.
 */
@RunWith(AndroidJUnit4::class)
class GpuOffloadTest {
    private val modelFile = File("/data/local/tmp/openweights/model.gguf")

    @Test
    fun generatesAReplyWithEveryLayerOnTheGpu() = runBlocking {
        assumeTrue("model.gguf not pushed", modelFile.exists())
        val engine = LlamaCppEngine()
        assumeTrue("no GPU backend on this device", engine.hasGpu())

        engine.use {
            it.load(modelFile, ModelLoadParams(contextLength = 1024, gpuLayers = 99))

            val gpu = it.computeDevices().first { device -> device.kind == ComputeDeviceKind.GPU }
            Log.i(TAG, "offloading to ${gpu.description}")

            val events = it.chat(
                messages = listOf(ChatMessage.text(ChatRole.USER, "Name one colour.")),
                params = SamplerParams(temperature = 0f, maxTokens = 24),
            ).toList()

            val completed = events.filterIsInstance<GenerationEvent.Completed>().single()
            Log.i(
                TAG,
                "gpu reply in ${completed.stats.decodeMs} ms, " +
                    "${completed.stats.generatedTokens} tokens, " +
                    "%.1f tok/s decode, %d ms to first token".format(
                        completed.stats.decodeTokensPerSecond,
                        completed.stats.timeToFirstTokenMs,
                    ),
            )

            // The point of the test: real text came back, so the graph ran somewhere that
            // works rather than erroring out into an empty reply.
            assertThat(completed.content.ifEmpty { completed.reasoning }).isNotEmpty()
            assertThat(completed.stats.generatedTokens).isGreaterThan(0)
        }
    }

    private fun LlamaCppEngine.hasGpu() = computeDevices().any { it.kind == ComputeDeviceKind.GPU }

    private companion object {
        const val TAG = "OpenWeightsTest"
    }
}

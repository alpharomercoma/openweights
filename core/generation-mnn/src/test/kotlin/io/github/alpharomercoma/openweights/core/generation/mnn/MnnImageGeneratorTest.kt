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

package io.github.alpharomercoma.openweights.core.generation.mnn

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.generation.Artifact
import io.github.alpharomercoma.openweights.core.generation.GenerationBundle
import io.github.alpharomercoma.openweights.core.generation.GenerationEvent
import io.github.alpharomercoma.openweights.core.generation.GenerationRuntime
import io.github.alpharomercoma.openweights.core.generation.GenerationTask
import io.github.alpharomercoma.openweights.core.generation.ImageRequest
import io.github.alpharomercoma.openweights.core.generation.ImageSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The generator, without a phone or a gigabyte of weights.
 *
 * Everything asserted here is this project's own: which requests are refused and why, what a
 * cancelled run leaves on disk, and whether a runtime that says it wrote a picture is
 * believed. None of that needs MNN, and all of it is what stands between a person and a
 * gallery full of entries that open onto nothing.
 *
 * What these cannot show is that MNN draws a lighthouse. That needs the converted bundle,
 * which this repository does not contain and whose licence has not been reviewed for
 * distribution, and a device. It is measured there and labelled as measured there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MnnImageGeneratorTest {
    private val folder: File = Files.createTempDirectory("openweights-generation").toFile()
    private val bundleFolder = File(folder, "sd15").apply { mkdirs() }
    private val outputs = File(folder, "generated")
    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    /** Stands in for the native library: scriptable, and it never needs a phone. */
    private class FakeBridge(
        var loadResult: Long = 1,
        var outcome: MnnOutcome = MnnOutcome.FINISHED,
        var backend: String = "OpenCL",
        /** Written when a run finishes, standing in for what the runtime would write. */
        var writes: Boolean = true,
        var stepsReported: Int = 0,
    ) : MnnBridge {
        override var onStep: ((Int) -> Unit)? = null
        var cancels = 0
            private set
        var releases = 0
            private set
        var lastPrompt: String? = null
        var lastSeed: Int? = null

        override fun load(modelPath: String, modelType: Int, backendType: Int, memoryMode: Int) =
            loadResult

        override fun generate(
            handle: Long,
            prompt: String,
            outputPath: String,
            steps: Int,
            seed: Int,
        ): Int {
            lastPrompt = prompt
            lastSeed = seed
            repeat(stepsReported) { onStep?.invoke(it + 1) }
            if (outcome == MnnOutcome.FINISHED && writes) {
                File(outputPath).writeBytes(ByteArray(64))
            }
            return outcome.ordinal
        }

        override fun cancel(handle: Long) {
            cancels++
        }

        override fun backend(handle: Long) = backend

        override fun release(handle: Long) {
            releases++
        }

        var voiceHandle: Long = 1
        var samples: Int = 44_100
        var voiceWrites: Boolean = true
        var speaker: String? = null
        var voiceReleases = 0
            private set

        var sanaOutcome: MnnOutcome = MnnOutcome.FINISHED
        var lastSanaPrompt: String? = null

        override fun runSana(request: SanaRequest): Int {
            lastSanaPrompt = request.prompt
            if (sanaOutcome == MnnOutcome.FINISHED && writes) {
                File(request.outputPath).writeBytes(ByteArray(64))
            }
            return sanaOutcome.ordinal
        }

        override fun loadVoice(modelsDir: String, speakerId: String): Long {
            speaker = speakerId
            return voiceHandle
        }

        override fun speak(handle: Long, text: String, outputPath: String): Int {
            if (samples > 0 && voiceWrites) File(outputPath).writeBytes(ByteArray(128))
            return samples
        }

        override fun sampleRate(handle: Long) = 44_100

        override fun setSpeaker(handle: Long, speakerId: String) {
            speaker = speakerId
        }

        override fun releaseVoice(handle: Long) {
            voiceReleases++
        }
    }

    private fun completeBundle(modelType: Int = NativeMnn.STABLE_DIFFUSION_1_5) {
        MnnImageGenerator.requiredFilesFor(modelType).forEach {
            val file = File(bundleFolder, it)
            file.parentFile.mkdirs()
            file.writeBytes(ByteArray(8))
        }
    }

    private fun bundle(modelType: Int = NativeMnn.STABLE_DIFFUSION_1_5) = GenerationBundle(
        id = "sd15",
        displayName = "Stable Diffusion 1.5",
        task = GenerationTask.IMAGE,
        runtime = GenerationRuntime.MNN,
        files = listOf(Artifact(File(bundleFolder, "unet.mnn").absolutePath, "application/mnn")),
        quantization = "fp16",
        minimumFreeBytes = 0,
        licence = "CreativeML Open RAIL-M",
        mnnModelType = modelType,
    )

    private fun sanaBundle() = GenerationBundle(
        id = "sana",
        displayName = "Sana Edit V2",
        task = GenerationTask.IMAGE,
        runtime = GenerationRuntime.MNN,
        files = listOf(Artifact(File(bundleFolder, "transformer.mnn").absolutePath, "application/mnn")),
        quantization = "q4_k",
        minimumFreeBytes = 0,
        licence = "Apache 2.0",
        mnnModelType = NativeMnn.SANA_DIFFUSION,
    )

    private fun generator(bridge: FakeBridge, available: Boolean = true) = MnnImageGenerator(
        outputDirectory = outputs,
        bridge = bridge,
        available = available,
        dispatcher = dispatcher,
        clock = { 1_000 },
    )

    private fun request(
        prompt: String = "a lighthouse",
        size: ImageSize = ImageSize(512, 512),
        steps: Int = 10,
        negative: String = "",
        seed: Long? = 42,
    ) = ImageRequest(
        prompt = prompt,
        negativePrompt = negative,
        size = size,
        steps = steps,
        guidance = 7.5f,
        seed = seed,
    )

    @Test
    fun `a bundle missing its weight files is refused by name`() = runTest(dispatcher) {
        // The seven-file shape is the one that bites: MNN's README names four, and the
        // conversion writes a `.mnn.weight` beside each `.mnn`. Checked for four, a bundle
        // passes with the three largest files absent and fails inside the runtime.
        MnnImageGenerator.SD15_REQUIRED_FILES.filterNot { it.endsWith(".weight") }
            .forEach { File(bundleFolder, it).writeBytes(ByteArray(8)) }

        val failure = runCatching { generator(FakeBridge()).load(bundle()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(GenerationUnavailableException::class.java)
        assertThat(failure).hasMessageThat().contains("text_encoder.mnn.weight")
    }

    @Test
    fun `a build with no runtime says so rather than failing at the library`() =
        runTest(dispatcher) {
            completeBundle()

            val failure = runCatching {
                generator(FakeBridge(), available = false).load(bundle())
            }.exceptionOrNull()

            assertThat(failure).hasMessageThat().contains("does not include the image runtime")
        }

    @Test
    fun `a runtime that will not load says so`() = runTest(dispatcher) {
        completeBundle()

        val failure = runCatching {
            generator(FakeBridge(loadResult = 0)).load(bundle())
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("would not load")
    }

    @Test
    fun `capability names the backend that ran rather than the one asked for`() =
        runTest(dispatcher) {
            // OpenCL is requested every time. A phone whose driver refuses a context runs on
            // the CPU, and a measurement labelled OpenCL taken on the CPU is worse than no
            // measurement.
            completeBundle()
            val generator = generator(FakeBridge(backend = "CPU"))

            generator.load(bundle())

            assertThat(generator.capability?.backend).isEqualTo("CPU")
        }

    @Test
    fun `capability offers nothing this path cannot do`() = runTest(dispatcher) {
        completeBundle()
        val generator = generator(FakeBridge())

        generator.load(bundle())

        val can = requireNotNull(generator.capability)
        assertThat(can.sizes).containsExactly(ImageSize(512, 512))
        assertThat(can.supportsNegativePrompt).isFalse()
        assertThat(can.supportsPreview).isFalse()
        // Guidance is a single value, not a range: the path applies its own and takes no
        // argument, so a range would be a slider that reached nothing.
        assertThat(can.guidance.start).isEqualTo(can.guidance.endInclusive)
        assertThat(can.supportsCancellation).isTrue()
    }

    @Test
    fun `a finished run reports the file, the seed and the backend`() = runTest(dispatcher) {
        completeBundle()
        val bridge = FakeBridge(stepsReported = 3)
        val generator = generator(bridge)
        generator.load(bundle())

        val events = generator.generate(request()).toList()

        val done = events.filterIsInstance<GenerationEvent.Completed<Artifact>>().single()
        assertThat(File(done.output.path).exists()).isTrue()
        assertThat(done.output.mediaType).isEqualTo("image/png")
        assertThat(done.stats.seed).isEqualTo(42)
        assertThat(done.stats.backend).isEqualTo("OpenCL")
        assertThat(events.first()).isEqualTo(GenerationEvent.Started)
        assertThat(events.filterIsInstance<GenerationEvent.Progress>()).hasSize(3)
    }

    @Test
    fun `a run with no seed still reports the one it used`() = runTest(dispatcher) {
        // A picture nobody can ask for again is a picture nobody can iterate on.
        completeBundle()
        val generator = generator(FakeBridge())
        generator.load(bundle())

        val done = generator.generate(request(seed = null)).toList()
            .filterIsInstance<GenerationEvent.Completed<Artifact>>().single()

        assertThat(done.stats.seed).isGreaterThan(0L)
    }

    @Test
    fun `a cancelled run publishes nothing`() = runTest(dispatcher) {
        completeBundle()
        val generator = generator(FakeBridge(outcome = MnnOutcome.CANCELLED))
        generator.load(bundle())

        val events = generator.generate(request()).toList()

        assertThat(events.last()).isEqualTo(GenerationEvent.Cancelled)
        assertThat(outputs.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a failed run publishes nothing and is not reported as cancelled`() = runTest(dispatcher) {
        // Two different things to everything above: one is worth an error and one is not.
        completeBundle()
        val generator = generator(FakeBridge(outcome = MnnOutcome.FAILED))
        generator.load(bundle())

        val events = generator.generate(request()).toList()

        assertThat(events.last()).isInstanceOf(GenerationEvent.Failed::class.java)
        assertThat(outputs.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a runtime that claims a picture it did not write is not believed`() = runTest(dispatcher) {
        // Believing it puts a gallery entry on screen that opens onto nothing, which is
        // indistinguishable to the user from the app losing their picture.
        completeBundle()
        val generator = generator(FakeBridge(writes = false))
        generator.load(bundle())

        val events = generator.generate(request()).toList()

        assertThat(events.last()).isInstanceOf(GenerationEvent.Failed::class.java)
        assertThat((events.last() as GenerationEvent.Failed).reason).contains("did not write")
    }

    @Test
    fun `generating with nothing loaded is refused rather than crashing`() = runTest(dispatcher) {
        val events = generator(FakeBridge()).generate(request()).toList()

        assertThat(events.single()).isInstanceOf(GenerationEvent.Failed::class.java)
    }

    @Test
    fun `a size this model cannot draw is refused and says what it can`() = runTest(dispatcher) {
        completeBundle()
        val generator = generator(FakeBridge())
        generator.load(bundle())

        val events = generator.generate(request(size = ImageSize(1024, 1024))).toList()

        assertThat((events.single() as GenerationEvent.Failed).reason).contains("512 by 512")
    }

    @Test
    fun `a negative prompt this path ignores is refused rather than dropped`() =
        runTest(dispatcher) {
            // Silently ignoring it would let somebody spend two minutes of phone on a
            // request the runtime never read.
            completeBundle()
            val generator = generator(FakeBridge())
            generator.load(bundle())

            val events = generator.generate(request(negative = "blurry")).toList()

            assertThat((events.single() as GenerationEvent.Failed).reason)
                .contains("negative prompt")
        }

    @Test
    fun `an empty prompt is refused before the phone does any work`() = runTest(dispatcher) {
        completeBundle()
        val bridge = FakeBridge()
        val generator = generator(bridge)
        generator.load(bundle())

        generator.generate(request(prompt = "  ")).toList()

        assertThat(bridge.lastPrompt).isNull()
    }

    @Test
    fun `a step count outside what this path takes is refused`() = runTest(dispatcher) {
        completeBundle()
        val generator = generator(FakeBridge())
        generator.load(bundle())

        assertThat((generator.generate(request(steps = 0)).toList().single()))
            .isInstanceOf(GenerationEvent.Failed::class.java)
    }

    @Test
    fun `a guidance scale outside what this path takes is refused`() = runTest(dispatcher) {
        completeBundle()
        val generator = generator(FakeBridge())
        generator.load(bundle())

        val events = generator.generate(request().copy(guidance = 12.0f)).toList()

        assertThat((events.single() as GenerationEvent.Failed).reason)
            .contains("Guidance must be")
    }

    @Test
    fun `unloading releases the handle and forgets what it could do`() = runTest(dispatcher) {
        completeBundle()
        val bridge = FakeBridge()
        val generator = generator(bridge)
        generator.load(bundle())

        generator.unload()

        assertThat(bridge.releases).isEqualTo(1)
        assertThat(generator.capability).isNull()
    }

    @Test
    fun `loading twice releases the first rather than leaking it`() = runTest(dispatcher) {
        // A diffusion model is most of the phone's memory. Two held at once is the phone.
        completeBundle()
        val bridge = FakeBridge()
        val generator = generator(bridge)

        generator.load(bundle())
        generator.load(bundle())

        assertThat(bridge.releases).isEqualTo(1)
    }

    @Test
    fun `closing releases the handle without needing to suspend`() = runTest(dispatcher) {
        completeBundle()
        val bridge = FakeBridge()
        val generator = generator(bridge)
        generator.load(bundle())

        generator.close()

        assertThat(bridge.releases).isEqualTo(1)
    }

    @Test
    fun `cancelling with nothing running asks the runtime nothing`() = runTest(dispatcher) {
        val bridge = FakeBridge()

        generator(bridge).cancel()

        assertThat(bridge.cancels).isEqualTo(0)
    }

    @Test
    fun `a bundle for another runtime is refused`() = runTest(dispatcher) {
        completeBundle()

        val failure = runCatching {
            generator(FakeBridge()).load(bundle().copy(runtime = GenerationRuntime.LLAMA_CPP))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a speech bundle is refused by the image generator`() = runTest(dispatcher) {
        completeBundle()

        val failure = runCatching {
            generator(FakeBridge()).load(bundle().copy(task = GenerationTask.SPEECH))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Sana bundle loads with its own required files and model type`() = runTest(dispatcher) {
        completeBundle(modelType = NativeMnn.SANA_DIFFUSION)
        val bridge = FakeBridge()
        val generator = generator(bridge)

        generator.load(bundle(modelType = NativeMnn.SANA_DIFFUSION))

        assertThat(generator.capability).isNotNull()
    }

    @Test
    fun `Sana reports 512 and 1024 as supported sizes`() = runTest(dispatcher) {
        completeBundle(modelType = NativeMnn.SANA_DIFFUSION)
        val generator = generator(FakeBridge())

        generator.load(bundle(modelType = NativeMnn.SANA_DIFFUSION))

        val can = requireNotNull(generator.capability)
        assertThat(can.sizes).contains(ImageSize(512, 512))
        assertThat(can.sizes).contains(ImageSize(1024, 1024))
    }

    @Test
    fun `Sana accepts 1024 sized requests`() = runTest(dispatcher) {
        completeBundle(modelType = NativeMnn.SANA_DIFFUSION)
        val generator = generator(FakeBridge())
        generator.load(bundle(modelType = NativeMnn.SANA_DIFFUSION))

        // 1024×1024 must not be refused as it would be for SD 1.5.
        val events = generator.generate(
            request(size = ImageSize(1024, 1024)).copy(guidance = 4.5f)
        ).toList()

        assertThat(events.first()).isEqualTo(GenerationEvent.Started)
    }

    @Test
    fun `Sana missing its LLM files is refused`() = runTest(dispatcher) {
        // Write everything except the LLM subdirectory.
        MnnImageGenerator.SANA_REQUIRED_FILES
            .filterNot { it.startsWith("llm/") }
            .forEach {
                val file = File(bundleFolder, it)
                file.parentFile.mkdirs()
                file.writeBytes(ByteArray(8))
            }

        val failure = runCatching {
            generator(FakeBridge()).load(bundle(modelType = NativeMnn.SANA_DIFFUSION))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(GenerationUnavailableException::class.java)
        assertThat(failure).hasMessageThat().contains("llm/")
    }

    @Test
    fun `Sana generate routes through runSana rather than the legacy path`() = runTest(dispatcher) {
        completeBundle(modelType = NativeMnn.SANA_DIFFUSION)
        val bridge = FakeBridge()
        val generator = generator(bridge)
        generator.load(bundle(modelType = NativeMnn.SANA_DIFFUSION))

        generator.generate(request(prompt = "a cat").copy(guidance = 4.5f)).toList()

        // Sana should call runSana, not the legacy generate.
        assertThat(bridge.lastSanaPrompt).isEqualTo("a cat")
        assertThat(bridge.lastPrompt).isNull() // Legacy path NOT called.
    }

    @Test
    fun `Sana 1024 resolution is not refused`() = runTest(dispatcher) {
        completeBundle(modelType = NativeMnn.SANA_DIFFUSION)
        val bridge = FakeBridge()
        val generator = generator(bridge)
        generator.load(bundle(modelType = NativeMnn.SANA_DIFFUSION))

        val events = generator.generate(
            request(size = ImageSize(1024, 1024), steps = 5).copy(guidance = 4.5f)
        ).toList()

        assertThat(events.first()).isEqualTo(GenerationEvent.Started)
    }

    @Test
    fun `Sana cancelled run publishes nothing`() = runTest(dispatcher) {
        completeBundle(modelType = NativeMnn.SANA_DIFFUSION)
        val bridge = FakeBridge().also { it.sanaOutcome = MnnOutcome.CANCELLED }
        val generator = generator(bridge)
        generator.load(bundle(modelType = NativeMnn.SANA_DIFFUSION))

        val events = generator.generate(request().copy(guidance = 4.5f)).toList()

        assertThat(events.last()).isEqualTo(GenerationEvent.Cancelled)
        assertThat(outputs.listFiles().orEmpty()).isEmpty()
    }
}

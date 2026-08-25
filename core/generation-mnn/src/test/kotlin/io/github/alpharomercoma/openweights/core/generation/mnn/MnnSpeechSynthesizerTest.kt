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
import io.github.alpharomercoma.openweights.core.generation.SpeechRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A voice, which is not a picture with the nouns changed.
 *
 * There are no steps, so there is no progress worth reporting, and there is no interruption
 * point, so there is no stopping. Both of those are stated by the capability rather than
 * implied, and both are asserted here: a synthesizer that quietly did nothing when asked to
 * stop would let a caller unload a voice out from under a run still using it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MnnSpeechSynthesizerTest {
    private val folder: File = Files.createTempDirectory("openweights-speech").toFile()
    private val bundleFolder = File(folder, "supertonic").apply { mkdirs() }
    private val outputs = File(folder, "generated")
    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private class FakeVoice(
        var handleOut: Long = 1,
        var samples: Int = 44_100,
        var writes: Boolean = true,
    ) : MnnBridge {
        override var onStep: ((Int) -> Unit)? = null
        var speaker: String? = null
        var releases = 0
            private set
        var spoken: String? = null

        override fun load(modelPath: String, modelType: Int, backendType: Int, memoryMode: Int) = 0L
        override fun generate(
            handle: Long,
            prompt: String,
            outputPath: String,
            steps: Int,
            seed: Int,
        ) = 2
        override fun cancel(handle: Long) = Unit
        override fun backend(handle: Long) = ""
        override fun release(handle: Long) = Unit
        override fun runSana(request: SanaRequest) = 2

        override fun loadVoice(modelsDir: String, speakerId: String): Long {
            speaker = speakerId
            return handleOut
        }

        override fun speak(handle: Long, text: String, outputPath: String): Int {
            spoken = text
            if (samples > 0 && writes) File(outputPath).writeBytes(ByteArray(128))
            return samples
        }

        override fun sampleRate(handle: Long) = 44_100

        override fun setSpeaker(handle: Long, speakerId: String) {
            speaker = speakerId
        }

        override fun releaseVoice(handle: Long) {
            releases++
        }
    }

    private fun completeBundle() = MnnSpeechSynthesizer.REQUIRED_FILES.forEach {
        File(bundleFolder, it).writeBytes(ByteArray(8))
    }

    private fun bundle() = GenerationBundle(
        id = "supertonic",
        displayName = "Supertonic",
        task = GenerationTask.SPEECH,
        runtime = GenerationRuntime.MNN,
        files = listOf(
            Artifact(File(bundleFolder, "vocoder.mnn").absolutePath, "application/mnn"),
        ),
        quantization = "fp16",
        minimumFreeBytes = 0,
        licence = "Apache-2.0",
    )

    private fun synthesizer(bridge: MnnBridge, available: Boolean = true) = MnnSpeechSynthesizer(
        outputDirectory = outputs,
        bridge = bridge,
        available = available,
        dispatcher = dispatcher,
        clock = { 1_000 },
    )

    @Test
    fun `a bundle missing a model is refused by name`() = runTest(dispatcher) {
        MnnSpeechSynthesizer.REQUIRED_FILES.drop(1)
            .forEach { File(bundleFolder, it).writeBytes(ByteArray(8)) }

        val failure = runCatching { synthesizer(FakeVoice()).load(bundle()) }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("duration_predictor.mnn")
    }

    @Test
    fun `capability names the voices the weights contain and nothing else`() = runTest(dispatcher) {
        completeBundle()
        val voice = synthesizer(FakeVoice())

        voice.load(bundle())

        val can = requireNotNull(voice.capability)
        assertThat(can.voices).containsExactly("M1", "M2", "F1", "F2").inOrder()
        assertThat(can.supportsVoiceCloning).isFalse()
    }

    @Test
    fun `a finished utterance reports the file and how fast it was spoken`() = runTest(dispatcher) {
        completeBundle()
        val voice = synthesizer(FakeVoice())
        voice.load(bundle())

        val done = voice.synthesize(SpeechRequest(text = "Read this aloud")).toList()
            .filterIsInstance<GenerationEvent.Completed<Artifact>>().single()

        assertThat(File(done.output.path).exists()).isTrue()
        assertThat(done.output.mediaType).isEqualTo("audio/wav")
    }

    @Test
    fun `no progress is reported, because there is none to report`() = runTest(dispatcher) {
        // Supertonic returns the whole utterance from one call. A bar moving between Started
        // and the result would be a bar drawn from nothing.
        completeBundle()
        val voice = synthesizer(FakeVoice())
        voice.load(bundle())

        val events = voice.synthesize(SpeechRequest(text = "Read this")).toList()

        assertThat(events.filterIsInstance<GenerationEvent.Progress>()).isEmpty()
        assertThat(events.first()).isEqualTo(GenerationEvent.Started)
    }

    @Test
    fun `a runtime that claims audio it did not write is not believed`() = runTest(dispatcher) {
        completeBundle()
        val voice = synthesizer(FakeVoice(writes = false))
        voice.load(bundle())

        val events = voice.synthesize(SpeechRequest(text = "Read this")).toList()

        assertThat((events.last() as GenerationEvent.Failed).reason).contains("did not write")
    }

    @Test
    fun `a file that would not write is told apart from a voice that refused`() =
        runTest(dispatcher) {
            completeBundle()
            val voice = synthesizer(FakeVoice(samples = MnnSpeechSynthesizer.FILE_REFUSED))
            voice.load(bundle())

            val events = voice.synthesize(SpeechRequest(text = "Read this")).toList()

            assertThat(
                (events.last() as GenerationEvent.Failed).reason,
            ).contains("could not be saved")
        }

    @Test
    fun `nothing to read is refused before the phone does any work`() = runTest(dispatcher) {
        completeBundle()
        val bridge = FakeVoice()
        val voice = synthesizer(bridge)
        voice.load(bundle())

        voice.synthesize(SpeechRequest(text = "   ")).toList()

        assertThat(bridge.spoken).isNull()
    }

    @Test
    fun `more text than this voice reads at once is refused rather than started`() =
        runTest(dispatcher) {
            // This cannot be stopped, so everything past the bound is time the phone will
            // spend whatever the person does next.
            completeBundle()
            val bridge = FakeVoice()
            val voice = synthesizer(bridge)
            voice.load(bundle())

            val long = "a".repeat(MnnSpeechSynthesizer.MAX_CHARACTERS + 1)
            val events = voice.synthesize(SpeechRequest(text = long)).toList()

            assertThat(bridge.spoken).isNull()
            assertThat((events.single() as GenerationEvent.Failed).reason).contains("longer than")
        }

    @Test
    fun `a voice this bundle does not have is refused and the others are named`() =
        runTest(dispatcher) {
            completeBundle()
            val voice = synthesizer(FakeVoice())
            voice.load(bundle())

            val events = voice.synthesize(SpeechRequest(text = "hello", voice = "M9")).toList()

            assertThat((events.single() as GenerationEvent.Failed).reason).contains("M1")
        }

    @Test
    fun `a voice the bundle does have is selected before speaking`() = runTest(dispatcher) {
        completeBundle()
        val bridge = FakeVoice()
        val voice = synthesizer(bridge)
        voice.load(bundle())

        voice.synthesize(SpeechRequest(text = "hello", voice = "F2")).toList()

        assertThat(bridge.speaker).isEqualTo("F2")
    }

    @Test
    fun `cloning a recording is refused rather than ignored`() = runTest(dispatcher) {
        completeBundle()
        val voice = synthesizer(FakeVoice())
        voice.load(bundle())

        val request = SpeechRequest(
            text = "hello",
            speakerReference = Artifact("/tmp/me.wav", "audio/wav"),
        )

        assertThat((voice.synthesize(request).toList().single() as GenerationEvent.Failed).reason)
            .contains("cannot be cloned")
    }

    @Test
    fun `synthesizing with nothing loaded is refused rather than crashing`() = runTest(dispatcher) {
        val events = synthesizer(FakeVoice()).synthesize(SpeechRequest("hello")).toList()

        assertThat(events.single()).isInstanceOf(GenerationEvent.Failed::class.java)
    }

    @Test
    fun `an image bundle is refused by the voice`() = runTest(dispatcher) {
        completeBundle()

        val failure = runCatching {
            synthesizer(FakeVoice()).load(bundle().copy(task = GenerationTask.IMAGE))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a language this bundle does not support is refused`() = runTest(dispatcher) {
        completeBundle()
        val voice = synthesizer(FakeVoice())
        voice.load(bundle())

        val events = voice.synthesize(SpeechRequest(text = "hola", language = "es")).toList()

        assertThat((events.single() as GenerationEvent.Failed).reason).contains("en")
    }

    @Test
    fun `unloading releases the voice and forgets what it could do`() = runTest(dispatcher) {
        completeBundle()
        val bridge = FakeVoice()
        val voice = synthesizer(bridge)
        voice.load(bundle())

        voice.unload()

        assertThat(bridge.releases).isEqualTo(1)
        assertThat(voice.capability).isNull()
    }

    @Test
    fun `closing releases the voice without needing to suspend`() = runTest(dispatcher) {
        completeBundle()
        val bridge = FakeVoice()
        val voice = synthesizer(bridge)
        voice.load(bundle())

        voice.close()

        assertThat(bridge.releases).isEqualTo(1)
    }

    @Test
    fun `a build with no runtime says so`() = runTest(dispatcher) {
        completeBundle()

        val failure = runCatching {
            synthesizer(FakeVoice(), available = false).load(bundle())
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("does not include the voice runtime")
    }
}

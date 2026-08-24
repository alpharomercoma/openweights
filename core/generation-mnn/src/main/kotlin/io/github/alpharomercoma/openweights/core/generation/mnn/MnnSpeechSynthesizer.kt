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

import io.github.alpharomercoma.openweights.core.generation.Artifact
import io.github.alpharomercoma.openweights.core.generation.GenerationBundle
import io.github.alpharomercoma.openweights.core.generation.GenerationEvent
import io.github.alpharomercoma.openweights.core.generation.GenerationRuntime
import io.github.alpharomercoma.openweights.core.generation.GenerationStats
import io.github.alpharomercoma.openweights.core.generation.GenerationTask
import io.github.alpharomercoma.openweights.core.generation.SpeechCapability
import io.github.alpharomercoma.openweights.core.generation.SpeechRequest
import io.github.alpharomercoma.openweights.core.generation.SpeechSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Supertonic through MNN, and nothing it cannot actually do.
 *
 * A voice is not a picture and this is not the image generator with the nouns changed. There
 * are no steps, so there is no progress: `Process` takes text and returns the whole utterance,
 * which means a caller gets [GenerationEvent.Started] and then a result, and a progress bar
 * drawn from that would be a bar drawn from nothing.
 *
 * ### Why this cannot be stopped
 *
 * Supertonic offers no callback at all, so there is nowhere to interrupt it: unlike the
 * denoising loop, there is not even a per-step call to throw out of. Cancelling the coroutine
 * abandons the result and deletes the file, and the phone keeps working until the utterance
 * is finished. That is said in [SpeechCapability] rather than implied, and it is why the
 * length of what is being spoken is worth bounding before starting.
 */
class MnnSpeechSynthesizer internal constructor(
    private val outputDirectory: File,
    private val bridge: MnnBridge,
    private val available: Boolean,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) : SpeechSynthesizer {
    constructor(outputDirectory: File) : this(
        outputDirectory = outputDirectory,
        bridge = NativeMnn(),
        available = NativeMnn.isAvailable,
        dispatcher = Dispatchers.IO,
        clock = System::currentTimeMillis,
    )

    private val lock = Mutex()

    @Volatile
    private var handle: Long = 0

    override var capability: SpeechCapability? = null
        private set

    override suspend fun load(bundle: GenerationBundle) = lock.withLock {
        require(bundle.task == GenerationTask.SPEECH) {
            "${bundle.displayName} is not a speech bundle"
        }
        require(bundle.runtime == GenerationRuntime.MNN) {
            "${bundle.displayName} needs a runtime this synthesizer is not"
        }
        if (!available) {
            throw GenerationUnavailableException("This build does not include the voice runtime.")
        }

        val directory = bundle.directory()
        val missing = REQUIRED_FILES.filterNot { File(directory, it).isFile }
        if (missing.isNotEmpty()) {
            throw GenerationUnavailableException(
                "${bundle.displayName} is missing ${missing.size} of its files, " +
                    "starting with ${missing.first()}. Download it again.",
            )
        }

        unloadLocked()
        val opened = withContext(dispatcher) {
            bridge.loadVoice(directory.absolutePath, NativeMnn.SUPERTONIC_VOICES.first())
        }
        if (opened == 0L) {
            throw GenerationUnavailableException(
                "${bundle.displayName} would not load on this phone.",
            )
        }

        handle = opened
        capability = SpeechCapability(
            voices = NativeMnn.SUPERTONIC_VOICES,
            // One language. Supertonic's text processor indexes Unicode code points against
            // a table the bundle carries, and claiming more than the weights were trained on
            // would offer a language that comes back as noise.
            languages = listOf("en"),
            sampleRateHz = DEFAULT_SAMPLE_RATE,
            supportsVoiceCloning = false,
            backend = "CPU",
        )
    }

    override fun synthesize(request: SpeechRequest): Flow<GenerationEvent<Artifact>> = flow {
        val initialHandle = handle
        val initialCapability = capability
        if (initialHandle == 0L || initialCapability == null) {
            emit(GenerationEvent.Failed("No voice is loaded."))
            return@flow
        }
        refuse(request, initialCapability)?.let {
            emit(GenerationEvent.Failed(it))
            return@flow
        }

        lock.withLock {
            val open = handle
            val can = capability
            if (open == 0L || can == null) {
                emit(GenerationEvent.Failed("No voice is loaded."))
                return@withLock
            }
            refuse(request, can)?.let {
                emit(GenerationEvent.Failed(it))
                return@withLock
            }

            val target = File(outputDirectory.apply { mkdirs() }, "speech-${clock()}.wav")
            emit(GenerationEvent.Started)
            val startedAt = clock()

            val samples = try {
                withContext(dispatcher) {
                    request.voice?.let { bridge.setSpeaker(open, it) }
                    bridge.speak(open, request.text, target.absolutePath)
                }
            } catch (cancelled: CancellationException) {
                // Nothing to ask the runtime, because there is nothing to ask: the file goes
                // and the phone finishes the utterance anyway. See the class comment.
                target.delete()
                throw cancelled
            }

            publish(samples, target, open, can, request, startedAt)
        }
    }.flowOn(dispatcher)

    /**
     * Says how the utterance ended, and lets nothing through that is not true.
     *
     * Split from [synthesize] for the reason the image generator's is: above is a run being
     * set up and driven, and this is the one decision about whether a recording exists. The
     * two negative codes are kept apart because they are different problems, a runtime that
     * refused and a disk that would not take the file, and only one of them is worth telling
     * somebody to free up space.
     */
    private suspend fun FlowCollector<GenerationEvent<Artifact>>.publish(
        samples: Int,
        target: File,
        open: Long,
        can: SpeechCapability,
        request: SpeechRequest,
        startedAt: Long,
    ) {
        val reason = when {
            samples == FILE_REFUSED -> "The voice could not be saved."
            samples < 0 -> "The voice could not be generated."
            !target.isFile || target.length() == 0L ->
                "The runtime reported audio it did not write."
            else -> null
        }
        if (reason != null) {
            target.delete()
            emit(GenerationEvent.Failed(reason))
            return
        }

        val rate = bridge.sampleRate(open).takeIf { it > 0 } ?: can.sampleRateHz
        val spokenMillis = durationMillis(samples, rate)
        val total = clock() - startedAt
        emit(
            GenerationEvent.Completed(
                output = Artifact(target.absolutePath, "audio/wav"),
                stats = GenerationStats(
                    totalMillis = total,
                    // Milliseconds of work per second of audio, because there are no steps.
                    // It is the only speed question worth asking of a synthesizer: whether
                    // it runs faster than it speaks.
                    perStepMillis = if (spokenMillis > 0) {
                        total * MILLIS_PER_SECOND / spokenMillis
                    } else {
                        total
                    },
                    backend = can.backend,
                    peakBytes = 0,
                    seed = request.seed ?: 0,
                ),
            ),
        )
    }

    /** How long the utterance is, from the samples rather than by decoding the file back. */
    private fun durationMillis(samples: Int, sampleRate: Int): Long =
        if (sampleRate <= 0) 0 else samples.toLong() * MILLIS_PER_SECOND / sampleRate

    private fun refuse(request: SpeechRequest, can: SpeechCapability): String? = when {
        request.text.isBlank() -> "Say what to read."
        request.text.length > MAX_CHARACTERS ->
            "That is longer than this voice reads at once. Keep it under $MAX_CHARACTERS " +
                "characters, or split it."
        request.language != null && request.language !in can.languages ->
            "This voice bundle supports ${can.languages.joinToString()}."
        request.voice != null && request.voice !in can.voices ->
            "This voice bundle has ${can.voices.joinToString()}."
        request.speakerReference != null && !can.supportsVoiceCloning ->
            "This voice cannot be cloned from a recording."
        else -> null
    }

    /**
     * Does nothing, and says so rather than pretending.
     *
     * There is no interruption point in Supertonic to reach. Leaving this to silently do
     * nothing would be worse than the honest no: a caller that believed it had stopped a
     * one-minute utterance would go on to unload the voice underneath a run still using it.
     */
    override fun cancel() = Unit

    override suspend fun unload() = lock.withLock { unloadLocked() }

    private fun unloadLocked() {
        val open = handle
        handle = 0
        capability = null
        if (open != 0L) bridge.releaseVoice(open)
    }

    override fun close() {
        runCatching { runBlocking { unload() } }
    }

    private fun GenerationBundle.directory(): File {
        val first = files.firstOrNull()
            ?: throw GenerationUnavailableException("$displayName has no files.")
        return File(first.path).parentFile
            ?: throw GenerationUnavailableException("$displayName is not in a folder.")
    }

    companion object {
        /**
         * What a Supertonic bundle contains.
         *
         * Four models and two tables. Checked here for the same reason the image bundle is:
         * the runtime's own answer to a missing file is a throw with a message written for
         * whoever wrote the runtime, and which file is missing is something a person can act
         * on.
         */
        val REQUIRED_FILES = listOf(
            "duration_predictor.mnn",
            "text_encoder.mnn",
            "vector_estimator.mnn",
            "vocoder.mnn",
            "tts.json",
            "unicode_indexer.json",
        )

        /** Supertonic's own rate. Confirmed from the runtime after the first utterance. */
        const val DEFAULT_SAMPLE_RATE = 44_100

        /**
         * Longer than a paragraph and shorter than an article.
         *
         * Bounded because this cannot be stopped: everything past this point is time the
         * phone will spend whatever the user does next.
         */
        const val MAX_CHARACTERS = 2_000

        const val MILLIS_PER_SECOND = 1_000L

        /** What [MnnBridge.speak] returns when the WAV would not write. */
        const val FILE_REFUSED = -2
    }
}

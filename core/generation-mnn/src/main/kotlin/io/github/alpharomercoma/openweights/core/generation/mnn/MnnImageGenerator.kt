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
import io.github.alpharomercoma.openweights.core.generation.ImageCapability
import io.github.alpharomercoma.openweights.core.generation.ImageGenerator
import io.github.alpharomercoma.openweights.core.generation.ImageRequest
import io.github.alpharomercoma.openweights.core.generation.ImageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Raised when a bundle cannot be used, with the reason the person can act on. */
class GenerationUnavailableException(message: String) : Exception(message)

/**
 * Stable Diffusion 1.5 through MNN, and nothing it cannot actually do.
 *
 * The capability this reports is narrow because the path behind it is narrow. MNN's
 * `StableDiffusion::run` takes a prompt, an output path, a step count and a seed, and that
 * is the whole of it: no negative prompt, no size, no guidance scale. Reporting a range for
 * guidance because the type has a field for it, and then ignoring the value, would be the
 * settings sheet's old mistake in a new place. A request asking for something this cannot do
 * is refused and told why, rather than quietly given something else.
 *
 * ### What cancelling does, exactly
 *
 * MNN's denoising loop has no cancellation hook: the callback it takes returns nothing, so
 * there is no value that means stop. What there is, is a call per step, and the bridge
 * throws out of it. So a stop lands at the next step boundary, which on a phone is one
 * denoising step, and the step already running finishes first. No file is published for a
 * cancelled run.
 *
 * That is a real stop rather than an abandonment, which is why [ImageCapability] can say
 * `supportsCancellation = true` without it being a claim this cannot keep.
 */
class MnnImageGenerator internal constructor(
    private val outputDirectory: File,
    private val bridge: MnnBridge,
    private val available: Boolean,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) : ImageGenerator {
    constructor(outputDirectory: File) : this(
        outputDirectory = outputDirectory,
        bridge = NativeMnn(),
        available = NativeMnn.isAvailable,
        dispatcher = Dispatchers.IO,
        clock = System::currentTimeMillis,
    )

    /**
     * One generation at a time, and one load at a time.
     *
     * A diffusion model is most of the phone's memory, and MNN's Diffusion object holds
     * mutable denoising state across a run. Two runs sharing it would interleave into one
     * ruined picture each.
     */
    private val lock = Mutex()

    @Volatile
    private var handle: Long = 0

    @Volatile
    private var loaded: GenerationBundle? = null

    override var capability: ImageCapability? = null
        private set

    /**
     * Loads a bundle, or says which of the reasons it could not.
     *
     * Everything checkable is checked before the runtime is asked, because the runtime's own
     * answer to a missing file is a null pointer and a line in logcat. Which file is missing
     * is something a person can act on.
     */
    override suspend fun load(bundle: GenerationBundle) = lock.withLock {
        require(bundle.task == GenerationTask.IMAGE) {
            "${bundle.displayName} is not an image bundle"
        }
        require(bundle.runtime == GenerationRuntime.MNN) {
            "${bundle.displayName} needs a runtime this generator is not"
        }
        if (!available) {
            throw GenerationUnavailableException(
                "This build does not include the image runtime.",
            )
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
            bridge.load(
                modelPath = directory.absolutePath,
                modelType = NativeMnn.STABLE_DIFFUSION_1_5,
                backendType = NativeMnn.FORWARD_OPENCL,
                memoryMode = NativeMnn.MEMORY_KEEP_LOADED,
            )
        }
        if (opened == 0L) {
            throw GenerationUnavailableException(
                "${bundle.displayName} would not load on this phone.",
            )
        }

        handle = opened
        loaded = bundle
        // Asked of the runtime. A request for OpenCL on a phone whose driver refuses a
        // context falls back to the CPU, and reporting the request as the answer would put
        // a backend name next to a measurement that was taken on a different one.
        capability = capabilityOn(bridge.backend(opened))
    }

    override fun generate(request: ImageRequest): Flow<GenerationEvent<Artifact>> = flow {
        val bundle = loaded
        val initialHandle = handle
        if (bundle == null || initialHandle == 0L) {
            emit(GenerationEvent.Failed("No image model is loaded."))
            return@flow
        }
        refuse(request)?.let {
            emit(GenerationEvent.Failed(it))
            return@flow
        }

        lock.withLock {
            val open = handle
            val currentBundle = loaded
            if (open == 0L || currentBundle == null) {
                emit(GenerationEvent.Failed("No image model is loaded."))
                return@withLock
            }
            refuse(request)?.let {
                emit(GenerationEvent.Failed(it))
                return@withLock
            }

            // A seed of this app's own when none was given, so the result can be asked for
            // again. Reported in the stats either way, because a picture nobody can
            // reproduce is a picture nobody can iterate on.
            val seed = request.seed ?: (clock() and Int.MAX_VALUE.toLong())
            val target = File(outputDirectory.apply { mkdirs() }, "$seed-${clock()}.png")

            emit(GenerationEvent.Started)
            val startedAt = clock()
            val steps = mutableListOf<Int>()
            bridge.onStep = { steps += it }

            val outcome = try {
                withContext(dispatcher) {
                    MnnOutcome.of(
                        bridge.generate(
                            handle = open,
                            prompt = request.prompt,
                            outputPath = target.absolutePath,
                            steps = request.steps,
                            seed = seed.toInt(),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                // The coroutine went, not the button. Ask the runtime to stop too, or the
                // phone keeps denoising a picture nobody is waiting for.
                bridge.cancel(open)
                target.delete()
                throw cancelled
            } finally {
                bridge.onStep = null
            }

            // Emitted from the same collector rather than pushed as they arrive: a step
            // callback fires on the generating thread, and a flow may not be emitted to from
            // one it was not collected on. Reporting them after the fact keeps the count
            // true, and the count is what a progress bar is drawn from.
            steps.forEach { emit(GenerationEvent.Progress(it, request.steps)) }

            publish(outcome, target, request, seed, startedAt)
        }
    }.flowOn(dispatcher)

    /**
     * Says how the run ended, and lets nothing through that is not true.
     *
     * Separated from [generate] because the two are different jobs: above is a run being
     * set up and driven, and this is the one decision that decides whether a picture exists.
     * The three outcomes are kept apart all the way to here rather than collapsed into a
     * boolean, because a run somebody stopped is not a run that failed, and only one of them
     * is worth an error on screen.
     */
    private suspend fun FlowCollector<GenerationEvent<Artifact>>.publish(
        outcome: MnnOutcome,
        target: File,
        request: ImageRequest,
        seed: Long,
        startedAt: Long,
    ) {
        if (outcome != MnnOutcome.FINISHED) {
            target.delete()
            emit(
                if (outcome == MnnOutcome.CANCELLED) {
                    GenerationEvent.Cancelled
                } else {
                    GenerationEvent.Failed("The picture could not be generated.")
                },
            )
            return
        }
        if (!target.isFile || target.length() == 0L) {
            // The runtime said yes and there is nothing on disk. Believing it would put an
            // entry in the gallery that opens onto nothing, which to the person looking at
            // it is indistinguishable from the app having lost their picture.
            target.delete()
            emit(GenerationEvent.Failed("The runtime reported a picture it did not write."))
            return
        }

        val total = clock() - startedAt
        emit(
            GenerationEvent.Completed(
                output = Artifact(target.absolutePath, "image/png"),
                stats = GenerationStats(
                    totalMillis = total,
                    perStepMillis = if (request.steps > 0) total / request.steps else total,
                    backend = capability?.backend.orEmpty(),
                    // Not measured here. A number this could not take is worse than no
                    // number, and a caller can tell zero from a measurement.
                    peakBytes = 0,
                    seed = seed,
                ),
            ),
        )
    }

    /** Why this request cannot be run, or null. */
    private fun refuse(request: ImageRequest): String? {
        val can = capability ?: return "No image model is loaded."
        return when {
            request.prompt.isBlank() -> "Say what to draw."
            request.size !in can.sizes ->
                "This model draws at ${can.sizes.joinToString { "${it.width} by ${it.height}" }}."
            request.steps !in can.steps ->
                "Steps must be between ${can.steps.first} and ${can.steps.last}."
            request.negativePrompt.isNotBlank() && !can.supportsNegativePrompt ->
                "This model does not take a negative prompt."
            else -> null
        }
    }

    override fun cancel() {
        val open = handle
        if (open != 0L) bridge.cancel(open)
    }

    override suspend fun unload() = lock.withLock { unloadLocked() }

    private fun unloadLocked() {
        val open = handle
        handle = 0
        loaded = null
        capability = null
        if (open != 0L) bridge.release(open)
    }

    /**
     * Releases the native handle without suspending, for a caller that is being torn down.
     *
     * `AutoCloseable` cannot suspend, and a handle that outlives its owner is most of the
     * phone's memory held by nothing. It does not take the lock: a close racing a generation
     * is a bug in the caller, and blocking a teardown on a two-minute run would be worse
     * than the race.
     */
    override fun close() {
        unloadLocked()
    }

    private fun capabilityOn(backend: String) = ImageCapability(
        // One size, because MNN's Stable Diffusion path takes no width or height: it draws
        // at what the bundle was converted for, and the supplied conversion is 512.
        sizes = listOf(ImageSize(SD15_EDGE, SD15_EDGE)),
        steps = MIN_STEPS..MAX_STEPS,
        // A single value rather than a range. The path applies its own fixed guidance and
        // there is no argument to change it, so a range would be a control that reached
        // nothing.
        guidance = SD15_GUIDANCE..SD15_GUIDANCE,
        supportsNegativePrompt = false,
        supportsPreview = false,
        supportsCancellation = true,
        backend = backend.ifBlank { "CPU" },
    )

    private fun GenerationBundle.directory(): File {
        val first = files.firstOrNull()
            ?: throw GenerationUnavailableException("$displayName has no files.")
        // The bundle is a folder to MNN, which is handed a directory and opens the seven
        // files itself. Callers describe artefacts, so the folder is taken from one.
        return File(first.path).parentFile
            ?: throw GenerationUnavailableException("$displayName is not in a folder.")
    }

    companion object {
        /**
         * What a converted Stable Diffusion 1.5 bundle contains.
         *
         * Seven rather than the four MNN's README names, because its conversion script
         * passes `--saveExternalData=1` and each `.mnn` therefore has a `.mnn.weight`
         * sibling holding the actual weights. A bundle checked for four files passes with
         * the three biggest ones missing.
         */
        val REQUIRED_FILES = listOf(
            "text_encoder.mnn",
            "text_encoder.mnn.weight",
            "unet.mnn",
            "unet.mnn.weight",
            "vae_decoder.mnn",
            "vae_decoder.mnn.weight",
            "tokenizer.mtok",
        )

        const val SD15_EDGE = 512
        const val SD15_GUIDANCE = 7.5f

        /** One step produces noise; beyond fifty is minutes of phone for no visible gain. */
        const val MIN_STEPS = 1
        const val MAX_STEPS = 50
    }
}

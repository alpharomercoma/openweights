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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
        val required = requiredFilesFor(bundle.mnnModelType)
        val missing = required.filterNot { File(directory, it).isFile }
        if (missing.isNotEmpty()) {
            throw GenerationUnavailableException(
                "${bundle.displayName} is missing ${missing.size} of its files, " +
                    "starting with ${missing.first()}. Download it again.",
            )
        }

        unloadLocked()

        // Sana: nativeLoad creates a lightweight session (no diffusion pre-loaded)
        // because the actual diffusion load happens inside nativeRunSana per-call.
        // SD 1.5: nativeLoad creates the Diffusion and loads weights immediately.
        val opened = withContext(dispatcher) {
            bridge.load(
                modelPath = directory.absolutePath,
                modelType = bundle.mnnModelType,
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

    override fun generate(request: ImageRequest): Flow<GenerationEvent<Artifact>> = channelFlow {
        val bundle = loaded
        val initialHandle = handle
        if (bundle == null || initialHandle == 0L) {
            send(GenerationEvent.Failed("No image model is loaded."))
            return@channelFlow
        }
        refuse(request)?.let {
            send(GenerationEvent.Failed(it))
            return@channelFlow
        }

        lock.withLock {
            val open = handle
            val currentBundle = loaded
            if (open == 0L || currentBundle == null) {
                send(GenerationEvent.Failed("No image model is loaded."))
                return@withLock
            }
            refuse(request)?.let {
                send(GenerationEvent.Failed(it))
                return@withLock
            }

            // A seed of this app's own when none was given, so the result can be asked for
            // again. Reported in the stats either way, because a picture nobody can
            // Folded into Int range with mod, not coerceIn: the caller's seed is usually a
            // millisecond timestamp, far larger than Int.MAX_VALUE, so a clamp collapsed
            // every generation onto the same seed instead of varying it.
            val seed = (request.seed ?: clock()).mod(Int.MAX_VALUE.toLong())
            val target = File(outputDirectory.apply { mkdirs() }, "$seed-${clock()}.png")

            send(GenerationEvent.Started)
            val startedAt = clock()
            bridge.onStep = { step ->
                trySend(GenerationEvent.Progress(step, request.steps))
            }

            val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion {
                bridge.cancel(open)
            }

            val outcome = try {
                withContext(dispatcher) {
                    if (currentBundle.mnnModelType == NativeMnn.SANA_DIFFUSION) {
                        MnnOutcome.of(
                            bridge.runSana(
                                SanaRequest(
                                    sessionHandle = open,
                                    resourcePath = currentBundle.directory().absolutePath,
                                    prompt = request.prompt,
                                    inputImagePath = request.referenceImage?.path.orEmpty(),
                                    outputPath = target.absolutePath,
                                    width = request.size.width,
                                    height = request.size.height,
                                    steps = request.steps,
                                    seed = seed.toInt(),
                                    useCfg = true,
                                    cfgScale = request.guidance,
                                    backendType = NativeMnn.FORWARD_OPENCL,
                                    memoryMode = NativeMnn.MEMORY_KEEP_LOADED,
                                ),
                            ),
                        )
                    } else {
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
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    bridge.cancel(open)
                    target.delete()
                }
                throw cancelled
            } finally {
                cancellationHandle.dispose()
                bridge.onStep = null
            }

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
    private suspend fun ProducerScope<GenerationEvent<Artifact>>.publish(
        outcome: MnnOutcome,
        target: File,
        request: ImageRequest,
        seed: Long,
        startedAt: Long,
    ) {
        if (outcome != MnnOutcome.FINISHED) {
            target.delete()
            send(
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
            send(GenerationEvent.Failed("The runtime reported a picture it did not write."))
            return
        }

        val total = clock() - startedAt
        send(
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
            request.guidance !in can.guidance ->
                "Guidance must be ${can.guidance.start}."
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
     * phone's memory held by nothing. Synchronized to avoid use-after-free with any in-flight native call.
     */
    override fun close() {
        cancel()
        runCatching { runBlocking { unload() } }
    }

    private fun capabilityOn(backend: String) = when (loaded?.mnnModelType) {
        NativeMnn.SANA_DIFFUSION -> ImageCapability(
            // Sana supports multiple resolutions through its config. The bundle is
            // converted at 512 but the model accepts 512–1024.
            sizes = listOf(ImageSize(512, 512), ImageSize(1024, 1024)),
            steps = SANA_MIN_STEPS..SANA_MAX_STEPS,
            guidance = SANA_MIN_GUIDANCE..SANA_MAX_GUIDANCE,
            defaultGuidance = SANA_GUIDANCE,
            supportsNegativePrompt = false,
            supportsPreview = false,
            supportsCancellation = true,
            // The "Edit" in Sana Edit V2: the native run() already branches text2img/img2img on
            // whether an input image path is non-empty. SD 1.5 has no such branch.
            supportsImageEdit = true,
            backend = backend.ifBlank { "CPU" },
        )
        else -> ImageCapability(
            // SD 1.5 and any other legacy path: fixed 512, no CFG control.
            sizes = listOf(ImageSize(SD15_EDGE, SD15_EDGE)),
            steps = MIN_STEPS..MAX_STEPS,
            guidance = SD15_GUIDANCE..SD15_GUIDANCE,
            defaultGuidance = SD15_GUIDANCE,
            supportsNegativePrompt = false,
            supportsPreview = false,
            supportsCancellation = true,
            backend = backend.ifBlank { "CPU" },
        )
    }

    private fun GenerationBundle.directory(): File {
        val first = files.firstOrNull()
            ?: throw GenerationUnavailableException("$displayName has no files.")
        // The bundle is a folder to MNN, which is handed a directory and opens the seven
        // files itself. Callers describe artefacts, so the folder is taken from one.
        return File(first.path).parentFile
            ?: throw GenerationUnavailableException("$displayName is not in a folder.")
    }

    companion object {
        /** True when the underlying native library (libopenweights_generation.so) is present. */
        val isAvailable: Boolean get() = NativeMnn.isAvailable

        /**
         * The files a bundle of [modelType] must contain, checked before the runtime
         * is asked so the answer names a missing file rather than crashing in native code.
         */
        internal fun requiredFilesFor(modelType: Int): List<String> = when (modelType) {
            NativeMnn.SANA_DIFFUSION -> SANA_REQUIRED_FILES
            else -> SD15_REQUIRED_FILES
        }

        /**
         * What a converted Stable Diffusion 1.5 bundle contains.
         *
         * Seven rather than the four MNN's README names, because its conversion script
         * passes `--saveExternalData=1` and each `.mnn` therefore has a `.mnn.weight`
         * sibling holding the actual weights. A bundle checked for four files passes with
         * the three biggest ones missing.
         */
        val SD15_REQUIRED_FILES = listOf(
            "text_encoder.mnn",
            "text_encoder.mnn.weight",
            "unet.mnn",
            "unet.mnn.weight",
            "vae_decoder.mnn",
            "vae_decoder.mnn.weight",
            "tokenizer.mtok",
            "vocab.json",
            "merges.txt",
            "alphas.txt",
        )

        /**
         * What a converted Sana Edit V2 bundle contains.
         *
         * Sana uses an LLM (Qwen3-0.6B) for prompt encoding instead of CLIP, so its file
         * layout includes an llm/ subdirectory. The config.json names every model file
         * and MNN reads it, but the files must still exist on disk.
         */
        val SANA_REQUIRED_FILES = listOf(
            "config.json",
            "connector.mnn",
            "connector.mnn.weight",
            "projector.mnn",
            "projector.mnn.weight",
            "transformer.mnn",
            "transformer.mnn.weight",
            "vae_decoder.mnn",
            "vae_decoder.mnn.weight",
            "vae_encoder.mnn",
            "vae_encoder.mnn.weight",
            "llm/llm.mnn",
            "llm/llm.mnn.weight",
            "llm/meta_queries.mnn",
            "llm/tokenizer.txt",
            "llm/llm.mnn.json",
        )

        const val SD15_EDGE = 512
        const val SD15_GUIDANCE = 7.5f

        // 4.5 looked plausible on paper (it is SD1.5's own scale in the same file) but measured
        // badly under-converged for this Sana checkpoint: the denoised latent's std came out at
        // roughly a third of a real encoded image's (0.49 vs. ~1.3, measured against
        // vae_encoder() on a real photo). 15 was the lowest scale tested that reliably closed
        // that gap (std ~1.2-1.3) and turned flat halftone haze into genuine image structure.
        const val SANA_GUIDANCE = 15.0f
        const val SANA_MIN_GUIDANCE = 1.0f
        const val SANA_MAX_GUIDANCE = 25.0f
        const val SANA_MIN_STEPS = 1
        const val SANA_MAX_STEPS = 30

        /** One step produces noise; beyond fifty is minutes of phone for no visible gain. */
        const val MIN_STEPS = 1
        const val MAX_STEPS = 50
    }
}

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

package io.github.alpharomercoma.openweights.core.generation

import kotlinx.coroutines.flow.Flow

/**
 * Making a picture or a voice, which is a different job from writing words.
 *
 * ### Why this is not `InferenceEngine`
 *
 * The engine interface next door is chat shaped, and rightly so: it speaks of a KV cache, a
 * chat template, tools, tokens per second and a context window. A diffusion model has none
 * of those. It has a step count, a guidance scale and a seed, and it produces one artefact
 * at the end rather than a stream of tokens. Bending one interface around both would give
 * every caller a set of parameters that are meaningless half the time, which is the mistake
 * the settings sheet already had to be rescued from.
 *
 * So this module is pure Kotlin with no JNI and no runtime in it. A runtime that can
 * actually generate lives behind it, and the chat path pays neither its native size nor its
 * initialisation cost.
 *
 * ### What is behind it today: nothing, on purpose
 *
 * Researched before being built, and the research is the reason this is an interface rather
 * than an implementation. Of the four candidate runtimes, only MNN has both a maintained
 * Android diffusion path and a current Android TTS integration. ExecuTorch's Android
 * Stable Diffusion path has an open report of producing noise; Google's MediaPipe Image
 * Generator is marked deprecated on its own page; ONNX Runtime GenAI lists Stable Diffusion
 * as under development.
 *
 * And for **none** of the four is there a vendor published, reproducible latency and peak
 * memory measurement on a Snapdragon 8 class phone. Not a slow number: no number. So the
 * shape is committed to here and the runtime is chosen by measurement rather than by
 * reputation. See `docs/research/generation-runtimes.md`.
 */
interface ImageGenerator : AutoCloseable {
    /** What the loaded bundle can actually do. Empty until something is loaded. */
    val capability: ImageCapability?

    suspend fun load(bundle: GenerationBundle)

    fun generate(request: ImageRequest): Flow<GenerationEvent<Artifact>>

    fun cancel()

    suspend fun unload()
}

/** Making speech, which shares a lifecycle with [ImageGenerator] and nothing else. */
interface SpeechSynthesizer : AutoCloseable {
    val capability: SpeechCapability?

    suspend fun load(bundle: GenerationBundle)

    /** Emits PCM as it is produced, then one terminal result naming the finished file. */
    fun synthesize(request: SpeechRequest): Flow<GenerationEvent<Artifact>>

    fun cancel()

    suspend fun unload()
}

/**
 * What one runtime plus one set of weights can do, stated rather than assumed.
 *
 * The interface asks this instead of guessing, for the same reason the settings sheet asks
 * the loaded model what it emits. A screen that offers 1024 by 1024 on a bundle trained at
 * 512, or a voice the weights do not contain, is making a promise the runtime never made.
 */
data class ImageCapability(
    val sizes: List<ImageSize>,
    val steps: IntRange,
    val guidance: ClosedFloatingPointRange<Float>,
    /** Where a fresh request should start on [guidance]; not necessarily its midpoint. */
    val defaultGuidance: Float,
    val supportsNegativePrompt: Boolean,
    /** True when the runtime can hand back a usable picture mid generation. */
    val supportsPreview: Boolean,
    /**
     * True when a run can actually be stopped, rather than abandoned.
     *
     * Stated because it is not a property of the app: it is a property of whether the
     * runtime's inner loop offers anywhere to interrupt it. A generator that reported a run
     * as cancelled while the phone went on computing it for another minute would be lying
     * about the one thing a stop button is for, so a caller that needs to know asks.
     */
    val supportsCancellation: Boolean,
    /** True when the runtime can edit a reference image rather than only generating from text. */
    val supportsImageEdit: Boolean = false,
    val backend: String,
)

data class SpeechCapability(
    val voices: List<String>,
    val languages: List<String>,
    val sampleRateHz: Int,
    val supportsVoiceCloning: Boolean,
    val backend: String,
)

data class ImageSize(val width: Int, val height: Int)

/**
 * One request for a picture.
 *
 * Every field here is a parameter a diffusion runtime actually reads, which is the point of
 * a separate type: none of temperature, top-p, repeat penalty or a context window appears,
 * because none of them exists on this side.
 */
data class ImageRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val size: ImageSize,
    val steps: Int,
    val guidance: Float,
    /** Null for a new one each time, which is the right default for a creative tool. */
    val seed: Long? = null,
    /** A picture to edit rather than start from noise, for runtimes whose capability says they can. */
    val referenceImage: Artifact? = null,
)

data class SpeechRequest(
    val text: String,
    val voice: String? = null,
    val language: String? = null,
    /** A reference recording to clone, for runtimes whose capability says they can. */
    val speakerReference: Artifact? = null,
    val seed: Long? = null,
)

/**
 * Something on disk, named by a path rather than by a `java.io.File`.
 *
 * `File` is a JVM type. It is also the obvious thing to put here, which is why this module's
 * first version had it in four places and could not compile for iOS: an interface meant to
 * outlive one runtime had a platform in its signature before any runtime existed behind it.
 *
 * A path and a media type is what every caller actually needed. The Android side turns it
 * into a `File`, an iOS side would turn it into an `NSURL`, and neither has to be named here.
 */
data class Artifact(
    /** An absolute path on the device's own filesystem. */
    val path: String,
    /** IANA type, so a caller knows what it is holding without reading it. */
    val mediaType: String,
)

/** How a generation is going, and how it ended. */
sealed interface GenerationEvent<out T> {
    data object Started : GenerationEvent<Nothing>

    /**
     * Progress, and only progress that is true.
     *
     * [preview] is null unless the runtime genuinely produced an intermediate image. A
     * fabricated preview, or a bar that moves on a timer, is the kind of thing that makes
     * every other number in an app suspect.
     */
    data class Progress(val step: Int, val totalSteps: Int, val preview: Artifact? = null) :
        GenerationEvent<Nothing>

    data class Completed<T>(val output: T, val stats: GenerationStats) : GenerationEvent<T>

    data class Failed(val reason: String) : GenerationEvent<Nothing>

    data object Cancelled : GenerationEvent<Nothing>
}

/**
 * What one generation actually cost, measured rather than claimed.
 *
 * Recorded because there is no published figure for any of the candidate runtimes on a
 * phone, so this app's own numbers are the only ones it can honestly show. [backend] is what
 * ran rather than what was asked for, which is the same distinction the chat path had to
 * learn: asking for the GPU and getting it are different things.
 */
data class GenerationStats(
    val totalMillis: Long,
    val perStepMillis: Long,
    val backend: String,
    val peakBytes: Long,
    val seed: Long,
)

/**
 * A runtime, its weights, and everything the interface must not guess.
 *
 * A manifest rather than a file path, because "an image model" is not enough to load one:
 * the runtime, the format, the quantization and the sizes it was trained at all have to
 * travel with the weights, and a chip specific artefact has to say which chip.
 */
data class GenerationBundle(
    val id: String,
    val displayName: String,
    val task: GenerationTask,
    val runtime: GenerationRuntime,
    val files: List<Artifact>,
    val quantization: String,
    /** What the phone needs free to load this, so a doomed load can be refused early. */
    val minimumFreeBytes: Long,
    /**
     * The chip this artefact was compiled for, or null for a portable one.
     *
     * Named because a Qualcomm NPU artefact is not portable: the offline converter takes a
     * target SoC and Hexagon architecture, so an 8 Gen 2 binary is not an 8 Gen 3 binary.
     * A bundle that does not say which chip it is for will eventually be loaded on the
     * wrong one.
     */
    val targetSoc: String? = null,
    val licence: String,
    /**
     * MNN's `DiffusionModelType` enum value. Meaningless for non-MNN runtimes.
     *
     * An Int rather than a sealed class so `:generation` stays KMP-pure with no MNN
     * dependency. The MNN backend reads it; everything else ignores it.
     */
    val mnnModelType: Int = 0,
)

enum class GenerationTask { IMAGE, SPEECH }

/**
 * Which runtime a bundle needs.
 *
 * An enum rather than a string so that a bundle for a runtime this build does not ship is
 * a compile time concept rather than a crash at load. llama.cpp is here and will never
 * generate a picture: it is listed so that speech, which its vendored `libmtmd` can already
 * do, has somewhere to say so.
 */
enum class GenerationRuntime { LLAMA_CPP, MNN }

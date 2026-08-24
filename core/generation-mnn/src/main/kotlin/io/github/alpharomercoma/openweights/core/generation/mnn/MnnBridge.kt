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

import android.util.Log

/**
 * The Kotlin half of the JNI surface, and the only place `System.loadLibrary` is called.
 *
 * An interface with a real implementation behind it rather than a set of top-level external
 * functions, because everything above this has to be testable on a host where there is no
 * native library at all, and a `external fun` cannot be substituted. The tests use a fake;
 * the phone uses [NativeMnn].
 */
internal interface MnnBridge {
    /** A handle, or 0 when the bundle would not load. */
    fun load(modelPath: String, modelType: Int, backendType: Int, memoryMode: Int): Long

    /** [MnnOutcome] as an int, so the boundary carries no Kotlin types. */
    fun generate(handle: Long, prompt: String, outputPath: String, steps: Int, seed: Int): Int

    fun cancel(handle: Long)

    /** What actually ran, which is not always what was asked for. */
    fun backend(handle: Long): String

    fun release(handle: Long)

    /** Set for the duration of one generation, so native code can report progress. */
    var onStep: ((Int) -> Unit)?
}

/** How one generation ended, as the three answers the caller has to tell apart. */
internal enum class MnnOutcome {
    FINISHED,

    /** Stopped between steps, so nothing was published and nothing is worth reporting. */
    CANCELLED,

    /** The runtime refused. Distinct from cancelled, because only this is worth an error. */
    FAILED,

    ;

    companion object {
        fun of(code: Int): MnnOutcome = when (code) {
            0 -> FINISHED
            1 -> CANCELLED
            else -> FAILED
        }
    }
}

/**
 * The real bridge, which exists only in a build that compiled the native libraries.
 *
 * [isAvailable] is asked rather than assumed. A build made without `openweights.mnn=true`
 * has no `libopenweights_generation.so` in it, and the honest answer to "can this phone
 * generate a picture" is then no, in exactly the same words as when the libraries are there
 * and no bundle has been downloaded.
 */
internal class NativeMnn : MnnBridge {
    override var onStep: ((Int) -> Unit)? = null

    override fun load(modelPath: String, modelType: Int, backendType: Int, memoryMode: Int) =
        nativeLoad(modelPath, modelType, backendType, memoryMode)

    override fun generate(
        handle: Long,
        prompt: String,
        outputPath: String,
        steps: Int,
        seed: Int,
    ) = nativeGenerate(handle, prompt, outputPath, steps, seed)

    override fun cancel(handle: Long) = nativeCancel(handle)

    override fun backend(handle: Long): String = nativeBackend(handle)

    override fun release(handle: Long) = nativeRelease(handle)

    /**
     * Called from the generating thread by native code.
     *
     * Whatever this throws is cleared on the other side rather than allowed to reach the
     * next JNI call, so a listener with a bug costs its own callback and not the picture.
     */
    @Suppress("unused") // Called by name from generation_jni.cpp.
    private fun onNativeStep(step: Int) {
        onStep?.invoke(step)
    }

    private external fun nativeLoad(
        modelPath: String,
        modelType: Int,
        backendType: Int,
        memoryMode: Int,
    ): Long

    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        outputPath: String,
        steps: Int,
        seed: Int,
    ): Int

    private external fun nativeCancel(handle: Long)

    private external fun nativeBackend(handle: Long): String

    private external fun nativeRelease(handle: Long)

    companion object {
        /**
         * Whether this build can generate at all.
         *
         * Loaded once and remembered, including the failure. A build without the native
         * libraries throws `UnsatisfiedLinkError` here, which is an `Error` rather than an
         * `Exception` and would go straight past a `runCatching` written for the usual case:
         * caught explicitly so that a missing runtime is a screen saying so rather than the
         * process going down.
         */
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("openweights_generation")
                true
            } catch (missing: UnsatisfiedLinkError) {
                Log.i("OpenWeights", "this build has no MNN generation runtime", missing)
                false
            }
        }

        /** `STABLE_DIFFUSION_1_5` in MNN's `DiffusionModelType`. */
        const val STABLE_DIFFUSION_1_5 = 0

        /** `MNN_FORWARD_CPU` and `MNN_FORWARD_OPENCL` in MNN's `MNNForwardType`. */
        const val FORWARD_CPU = 0
        const val FORWARD_OPENCL = 3

        /**
         * MNN's memory mode 1, which keeps its modules loaded between runs.
         *
         * Modes 0 and 2 release modules during a run, so a second generation pays the whole
         * load again. On a phone that is the difference between a warm run and a cold one,
         * and this app generates repeatedly by design.
         */
        const val MEMORY_KEEP_LOADED = 1
    }
}

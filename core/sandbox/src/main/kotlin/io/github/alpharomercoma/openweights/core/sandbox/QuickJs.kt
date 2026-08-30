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

package io.github.alpharomercoma.openweights.core.sandbox

/** What running a script produced, and whether the script got there on purpose. */
data class ScriptResult(
    val output: String,
    val failed: Boolean,
    /**
     * Whether the failure was the sandbox rather than the program.
     *
     * The two want opposite treatment from whatever is holding the round budget. A program
     * that threw will throw again on the same source, so asking twice is a wasted round; a
     * runner that could not be bound, or a process the system reclaimed, has nothing to do
     * with the source and may well work on the next attempt.
     *
     * Only set where the sandbox itself failed outside the interpreter, so it never has to
     * survive the binder round trip that carries an ordinary result back.
     */
    val transient: Boolean = false,
)

/**
 * The interpreter itself, one runtime per call.
 *
 * Deliberately not reusable. A runtime kept between calls is faster and also lets one script
 * leave something behind for the next, which is state nobody reasons about correctly and a
 * place for one turn's mistake to become the next turn's confusion. These runs last
 * milliseconds; building the runtime is not what costs.
 *
 * The limits are arguments rather than constants because the only correct value depends on
 * what else is happening: this runs in a separate process on a phone that is also holding a
 * couple of gigabytes of model weights.
 */
internal class QuickJs {
    fun run(
        source: String,
        inputsJson: String,
        memoryBytes: Long,
        stackBytes: Long,
        millis: Long,
        outputLimit: Int,
    ): ScriptResult {
        // A one-element array because JNI has no clean way back for a second value, and a
        // sentinel inside the string would be a thing a script could write for itself.
        val failed = BooleanArray(1)
        val output = nativeRun(
            source,
            inputsJson,
            memoryBytes,
            stackBytes,
            millis,
            outputLimit,
            failed,
        )
        return ScriptResult(output = output, failed = failed[0])
    }

    private external fun nativeRun(
        source: String,
        inputsJson: String,
        memoryBytes: Long,
        stackBytes: Long,
        millis: Long,
        outputLimit: Int,
        failedOut: BooleanArray,
    ): String

    companion object {
        init {
            System.loadLibrary("openweights_sandbox")
        }
    }
}

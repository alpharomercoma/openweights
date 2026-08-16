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

package io.github.alpharomercoma.openweights.ui.chat

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * A precondition that a release run is not allowed to shrug at.
 *
 * The device tier is built on `assumeTrue`, and it has to be: these tests need weights that
 * are not in the repository, and a machine with no phone attached should not fail a suite it
 * cannot run. The cost is that a skip and a pass look identical in the runner's output. Every
 * one of these is a sentence about something that did not happen, and the runner prints "OK".
 *
 * That is not hypothetical here. `ToolTurnOnDeviceTest` reads whichever model is sitting at
 * `/data/local/tmp/openweights/model.gguf`, and it skips when that model's template renders
 * no tools and again when the model declines to call one. Push Gemma there and the entire
 * tool path reports itself green without a single tool ever having run; push Hammer, as
 * happened here, and it fails for a reason that belongs to the model rather than the code.
 * Either way the number at the bottom is the same number.
 *
 * So the assumption stays, and a run can be told to stop accepting it:
 *
 * ```
 * adb shell am instrument -w -e strict true io.github...debug.test/...
 * ```
 *
 * Ordinary runs behave exactly as before and say out loud what they skipped. A release run
 * passes `-e strict true` and a missing fixture becomes a failure, which is the only way a
 * green suite can mean the thing it appears to mean.
 */
internal object Fixtures {
    /** True when this run was told that a skipped precondition is a failure. */
    val strict: Boolean by lazy {
        InstrumentationRegistry.getArguments().getString("strict")?.toBoolean() ?: false
    }

    /**
     * Skips unless [holds], or fails when the run is strict.
     *
     * The message is logged either way, because "which of these actually ran" is a question
     * the runner's own output cannot answer and somebody always ends up asking it.
     */
    fun require(why: String, holds: Boolean) {
        if (holds) return
        Log.w(TAG, "${if (strict) "MISSING" else "SKIPPED"} $why")
        if (strict) {
            throw AssertionError(
                "$why. This run was started with -e strict true, where a precondition that " +
                    "does not hold is a failure rather than a skip: a release gate cannot be " +
                    "green because the fixture was absent.",
            )
        }
        assumeTrue(why, false)
    }

    private const val TAG = "OpenWeightsFixtures"
}

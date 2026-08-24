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

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the four rung ladder costs, measured rather than assumed.
 *
 * The worry it answers is real: a program that needs the last rung is compiled four times.
 * The claim being tested is that this costs nothing anybody notices, because a compile is
 * microseconds against a process hop of milliseconds, and because the common case is rung
 * one and pays nothing at all.
 *
 * Times are printed rather than asserted on. A phone under thermal load will produce a
 * different number and failing a build for that would be measuring the room.
 */
@RunWith(AndroidJUnit4::class)
class SandboxSpeedTest {
    private val sandbox = Sandbox(ApplicationProvider.getApplicationContext())

    private fun time(label: String, source: String): Long = runBlocking {
        // Warm first: the first run of the process pays for binding and for the runtime.
        repeat(WARMUP) { sandbox.run(source) }
        val runs = LongArray(RUNS) {
            val started = System.nanoTime()
            val result = sandbox.run(source)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertThat(result.failed).isFalse()
            elapsed
        }
        val median = runs.sorted()[RUNS / 2]
        Log.i(TAG, "$label median ${median}ms of ${runs.sorted().joinToString(",")}")
        median
    }

    @Test
    fun everyRungOfTheLadderIsMeasured() {
        // Rung one: correct on the first reading, which is what most programs are.
        val direct = time("rung1 plain", "const a = 6 * 7;\na")

        // Rung two: a top-level return, so one failed compile then a wrapped one.
        val wrapped = time("rung2 return", "const a = 6 * 7;\nreturn a;")

        // Rung three: top-level await, so two failed compiles then an async evaluation.
        val awaited = time("rung3 await", "const a = await Promise.resolve(42);\na")

        // Rung four: both, which is the worst case this ladder has.
        val worst = time("rung4 await+return", "const a = await Promise.resolve(42);\nreturn a;")

        // No fenced case here, and finding that out is worth recording. A fence is stripped
        // by `RunScriptTool.asProgram` before the sandbox is called, so this module never
        // sees one: handed a fence directly it fails, correctly, because ``` is a tagged
        // template literal and this module holds no policy about what a model tends to
        // write. That separation is deliberate and the host test `AsProgramTest` covers the
        // other side of it.

        Log.i(
            TAG,
            "ladder: plain=${direct}ms wrapped=${wrapped}ms await=${awaited}ms " +
                "worst=${worst}ms",
        )

        // The only assertion worth making: the worst case is not a different order of
        // magnitude from the best. Three extra compiles of a short program cannot double a
        // call that spends most of its time crossing into another process.
        assertThat(worst).isLessThan(direct * WORST_CASE_FACTOR + FLOOR_MS)
    }

    private companion object {
        const val TAG = "OpenWeights"
        const val WARMUP = 3
        const val RUNS = 9

        /** Generous, because this is a smoke test for an order of magnitude. */
        const val WORST_CASE_FACTOR = 3

        /** A floor, so a sub-millisecond baseline does not make the bound impossible. */
        const val FLOOR_MS = 40
    }
}

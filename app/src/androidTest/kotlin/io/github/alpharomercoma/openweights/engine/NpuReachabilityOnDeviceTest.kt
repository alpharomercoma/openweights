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

package io.github.alpharomercoma.openweights.engine

import android.util.Log
import org.junit.Test

/**
 * Whether an ordinary app process can reach this phone's NPU runtime at all.
 *
 * The vendor allowlist says it should: `libneuron_runtime.so` is named in
 * `/vendor/etc/public.libraries.txt`, which is what lets a non-system app out of its linker
 * namespace to a vendor library. Reading a file is not the same as the loader agreeing,
 * though, and the answer decides whether "target the NPU" is a question about engineering
 * or about permission. So it is asked from inside an app, not from an adb shell, which has
 * a different namespace and would answer an easier question.
 */
class NpuReachabilityOnDeviceTest {
    @Test
    fun whetherTheNpuRuntimeLoadsInAnAppProcess() {
        listOf(
            "neuron_runtime",
            "neuron_adapter_mgvi",
            "neuronusdk_adapter.mtk",
            "neuralnetworks",
        ).forEach { name ->
            val outcome = runCatching { System.loadLibrary(name) }
            Log.i(
                TAG,
                "System.loadLibrary($name) -> " +
                    (outcome.exceptionOrNull()?.message?.take(160) ?: "LOADED"),
            )
        }
    }

    private companion object {
        const val TAG = "OpenWeightsNpu"
    }
}

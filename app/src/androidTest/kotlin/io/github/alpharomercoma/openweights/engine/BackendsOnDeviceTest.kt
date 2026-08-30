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
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import org.junit.Test

/** What compute devices this phone actually offers the engine, in its own words. */
class BackendsOnDeviceTest {
    @Test
    fun whatBackendsThisPhoneOffers() {
        val engine = LlamaCppEngine()
        engine.computeDevices().forEach {
            Log.i(
                TAG,
                "device id=${it.id} kind=${it.kind} mem=${it.totalMemoryBytes} :: ${it.description}",
            )
        }
    }

    private companion object {
        const val TAG = "OpenWeightsBackends"
    }
}

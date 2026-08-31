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

package io.github.alpharomercoma.openweights.core.engine

import io.github.alpharomercoma.openweights.core.common.model.CompiledBackend

/**
 * Whether this build can run a model compiled ahead of time. It can: this is the
 * accelerated flavour, which carries the ExecuTorch runtime.
 *
 * Two files, one per flavour, rather than a runtime check. A class-presence test would
 * still drag the dependency into every build, and the 8.6 MB of native library per ABI is
 * the entire reason the flavour exists.
 */
object ExecuTorchSupport {
    const val AVAILABLE: Boolean = true

    /**
     * The delegates this build has linked, and therefore the models it can open.
     *
     * A `.pte` whose backend is missing does not fall back to the CPU — the runtime
     * reports it is not registered and the load fails outright. Checking before offering a
     * download is the difference between refusing a model up front and refusing it after a
     * gigabyte. UNKNOWN is included because most published exports are XNNPACK and do not
     * say so in their name; excluding it would hide nearly all of them.
     */
    val BACKENDS: Set<CompiledBackend> = setOf(CompiledBackend.XNNPACK, CompiledBackend.UNKNOWN)

    /** Whether this build could open a model compiled for [backend]. */
    fun canRun(backend: CompiledBackend): Boolean = backend in BACKENDS

    /** A bridge onto the real runtime. */
    fun bridge(): ExecuTorchBridge = NativeExecuTorchBridge()
}

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

    /** A bridge onto the real runtime. */
    fun bridge(): ExecuTorchBridge = NativeExecuTorchBridge()
}

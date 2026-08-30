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
 * Whether this build can run a model compiled ahead of time. It cannot: this is the
 * standard flavour, which ships llama.cpp alone.
 *
 * Nothing here should ever be reached. [AVAILABLE] is what callers ask, and a false answer
 * means `.pte` files are never offered, never listed and never downloaded — a model that
 * cannot run should not be visible, rather than visible and then refused.
 */
object ExecuTorchSupport {
    const val AVAILABLE: Boolean = false

    fun bridge(): ExecuTorchBridge =
        throw LlamaException("This build does not include the ExecuTorch runtime")
}

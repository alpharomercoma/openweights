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
 * Stands in for the ExecuTorch runtime in a build that does not ship it.
 *
 * The app lists every model file it finds, and `.pte` files are now among them, so a user
 * who sideloads one can select it before there is anything to run it with. Without this
 * they would get whatever failure fell out of the wiring; with it they get a sentence that
 * says what is actually wrong.
 *
 * Replaced by the real bridge once `org.pytorch:executorch-android` is a dependency.
 */
class UnavailableExecuTorchBridge : ExecuTorchBridge {

    override fun load(modelPath: String, tokenizerPath: String, temperature: Float): Boolean =
        throw LlamaException(
            "This build has no ExecuTorch runtime, so it cannot open a .pte model. " +
                "GGUF models run on llama.cpp as usual.",
        )

    override fun generate(
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): ExecuTorchOutcome = throw LlamaException("No ExecuTorch runtime in this build")

    override fun stop() = Unit

    override fun close() = Unit
}

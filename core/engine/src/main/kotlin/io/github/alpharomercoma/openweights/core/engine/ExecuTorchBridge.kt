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
 * How one generation ended, and what the runtime measured while doing it.
 *
 * Token counts come from ExecuTorch's own accounting rather than from anything counted on
 * this side, because the app has no tokenizer of its own: a `.pte` ships one, and it is
 * inside the runtime. A bridge that cannot report a count leaves it at zero, which
 * [GenerationStats] renders as "no number" rather than as a rate computed from a guess.
 */
data class ExecuTorchOutcome(
    val reason: StopReason,
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val prefillMs: Long = 0,
    val decodeMs: Long = 0,
)

/**
 * The ExecuTorch runtime, reduced to what an engine needs from it.
 *
 * An interface rather than a direct call into `org.pytorch:executorch-android` for one
 * reason worth the indirection: everything above it — templating, streaming, tool parsing,
 * how a cancelled turn unwinds — is ordinary logic that should be tested on a laptop, and
 * the real runtime only exists on a phone with a `.pte` on it. `FakeExecuTorchBridge` is
 * what makes [ExecuTorchEngine] testable at all.
 *
 * Implementations are not thread-safe. [stop] is the exception, and may be called from
 * another thread while [generate] is running.
 */
interface ExecuTorchBridge {

    /**
     * Opens a `.pte` and the tokenizer it was exported against.
     *
     * Both paths are required and neither is inferred: a `.pte` holds a compiled graph and
     * nothing that says which tokenizer produced it, so handing it the wrong one produces
     * fluent nonsense rather than an error.
     *
     * @return true when the model is ready to generate.
     */
    fun load(modelPath: String, tokenizerPath: String, temperature: Float): Boolean

    /**
     * Runs one generation, calling [onToken] with each fragment as it is produced.
     *
     * Blocking: callers run it off the main thread. There is no conversation state to carry
     * between calls — see [ExecuTorchEngine] for what that costs.
     */
    fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): ExecuTorchOutcome

    /**
     * Drops whatever the runtime is holding from previous generations.
     *
     * ExecuTorch does keep state between calls — `LlmModule` exposes both this and a
     * prefill-without-generating entry point — so this is a real operation rather than a
     * formality. Whether an ordinary [generate] *reuses* that state or starts from the
     * prompt it was given has not been measured yet, and the answer decides whether this
     * engine is viable for long conversations at all.
     */
    fun resetContext()

    /** Asks the running [generate] to stop. Safe from any thread; a no-op when idle. */
    fun stop()

    /** Releases the model. Safe to call when nothing is loaded. */
    fun close()
}

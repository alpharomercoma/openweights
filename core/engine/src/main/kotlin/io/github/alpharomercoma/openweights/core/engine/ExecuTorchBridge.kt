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
/**
 * What a `.pte` says about itself before it is opened for generation.
 *
 * @property contextLength the window it was exported with, when it states one.
 * @property hasVision whether it carries a `vision_encoder` method, which is how the
 * multimodal export layout announces itself: pictures go through that method into the
 * decoder as embeddings, in place of text.
 */
data class ExportFacts(val contextLength: Int?, val hasVision: Boolean)

interface ExecuTorchBridge {

    /**
     * Whether the runtime's tokenizer, given this `tokenizer.json`, prepends the model's BOS
     * itself. A Hugging Face tokenizer does so through a `TemplateProcessing` post-processor;
     * one saved without it (transformers 5 writes LFM2.5-2.6B's that way) yields the bare
     * text, and the runtime adds nothing of its own. Defaults to true, which is the shape
     * every publisher export this app knew before 2026-09-07 had.
     */
    fun tokenizerAddsBos(tokenizerPath: String): Boolean = true

    /**
     * Reads [ExportFacts] off the file through the plain module API, without opening it
     * for generation. Cheap: the file is mapped, nothing is run. Throws when the file
     * could not be read at all, which is not the same as a file that says nothing (codex
     * QA: a probe that failed once under memory pressure used to be remembered as
     * "text-only, no window" for the rest of the process).
     */
    fun probe(modelPath: String): ExportFacts = ExportFacts(exportedContextLength(modelPath), false)

    /**
     * Opens a `.pte` and the tokenizer it was exported against.
     *
     * Both paths are required and neither is inferred: a `.pte` holds a compiled graph and
     * nothing that says which tokenizer produced it, so handing it the wrong one produces
     * fluent nonsense rather than an error.
     *
     * @param contextLength the whole window, prompt and reply together. ExecuTorch counts
     * in total sequence length rather than in new tokens, so it belongs to the model rather
     * than to a call and is kept from here.
     * @param multimodal open with the multimodal runner, which is a different runner in the
     * runtime rather than a flag on the text one: it is the only one that can take a
     * picture, and it is what a file with a `vision_encoder` method was exported for.
     * @return true when the model is ready to generate.
     */
    fun load(
        modelPath: String,
        tokenizerPath: String,
        temperature: Float,
        contextLength: Int,
        multimodal: Boolean = false,
    ): Boolean

    /**
     * Feeds one picture into the cache at the current position, between text prefills.
     *
     * [pixels] are the encoder's input exactly: channels-first, `channels * height * width`
     * floats in 0..255. Not normalised, because the exports this app knows bake the
     * rescale and normalise into the graph, and normalising here would apply them twice.
     * The runtime runs the encoder and hands its output to the decoder as embeddings.
     */
    fun prefillImage(pixels: FloatArray, width: Int, height: Int, channels: Int): Unit =
        throw LlamaException("This runtime cannot read pictures")

    /**
     * The window the file was exported with, or null when the file does not say.
     *
     * A `.pte` carries its window as a constant method (`get_max_context_len`), and it is
     * the only number that matters: the runtime clamps to it whatever a caller asks for.
     * The engine used to report the user's preference instead, so a model exported at
     * 2048 was shown as 4096, the tool prefix alone took two thirds of the real window,
     * nothing upstream trimmed a search result to fit, and the runtime refused the turn
     * with "Max seq length exceeded" (2026-09-06, LFM2.5 1.2B xnnpack).
     */
    fun exportedContextLength(modelPath: String): Int? = null

    /**
     * Runs one generation, calling [onToken] with each fragment as it is produced.
     *
     * Blocking: callers run it off the main thread.
     *
     * @param maxNewTokens how many tokens to *add*, which is not how ExecuTorch's simpler
     * entry point counts. `generate(prompt, seqLen, ...)` takes a total sequence length, so
     * passing a reply budget of 24 against a 907-token prompt resolves to -883 new tokens
     * and fails with "Max new tokens 0 is less than or equal to 0". Measured on device; the
     * distinction is invisible until a prompt grows past the budget, which is to say until
     * a real conversation.
     */
    fun generate(prompt: String, maxNewTokens: Int, onToken: (String) -> Unit): ExecuTorchOutcome

    /**
     * Feeds [prompt] into the cache without generating anything.
     *
     * The runtime appends at wherever its position already is, exactly as [generate]
     * does, so what was fed here is text a later generate's prompt must begin with.
     * Blocking, and — unlike generation — not stoppable mid-call: [stop] gates the token
     * loop, and a prefill has no token loop. A caller that wants an interruptible warm
     * feeds the text in pieces and checks between them.
     */
    fun prefill(prompt: String)

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

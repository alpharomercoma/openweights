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

import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule

/**
 * The real ExecuTorch runtime, through `org.pytorch:executorch-android`.
 *
 * A thin adapter and deliberately nothing more: everything worth testing lives in
 * [ExecuTorchEngine] above it, against [ExecuTorchBridge] rather than against this.
 */
class NativeExecuTorchBridge : ExecuTorchBridge {

    private var module: LlmModule? = null

    // Written from the runtime's callback rather than returned by it, so they are held
    // here for the duration of one blocking generate rather than passed through.
    private var window: Int = 0
    private var lastStats: String? = null
    private var lastError: String? = null

    override fun load(
        modelPath: String,
        tokenizerPath: String,
        temperature: Float,
        contextLength: Int,
    ): Boolean {
        window = contextLength
        close()
        // Anything can come back from here: a missing native library throws
        // UnsatisfiedLinkError rather than an exception, and a .pte built for another
        // runtime version fails inside the loader. Both become the same failure the rest
        // of the app knows how to show.
        return runCatching {
            LlmModule(modelPath, tokenizerPath, temperature).also {
                it.load()
                module = it
            }
        }.fold(
            onSuccess = { true },
            onFailure = { cause ->
                throw LlamaException(
                    "ExecuTorch could not open this model: ${cause.message ?: cause::class.java.simpleName}",
                )
            },
        )
    }

    override fun generate(
        prompt: String,
        maxNewTokens: Int,
        onToken: (String) -> Unit,
    ): ExecuTorchOutcome {
        val running = module ?: throw LlamaException("No model loaded")

        lastStats = null
        lastError = null

        // The config form rather than generate(prompt, seqLen, ...), because only this
        // one separates "how long may the whole thing be" from "how much may be added".
        val config = LlmGenerationConfig.create()
            .maxNewTokens(maxNewTokens)
            // seqLen is deliberately not set. Setting both makes the runtime resolve the
            // allowance from the sequence length and ignore maxNewTokens: asking for 24
            // tokens behind a 907-token prompt produced 1141, which is 2048 - 907. The
            // model reports its own window through get_max_seq_len and clamps to it, so
            // leaving this alone is both correct and what makes the budget mean anything.
            // Echo replays the prompt through onResult, and the whole conversation would
            // arrive looking like something the model had written.
            .echo(false)
            // Explicit, because this one is applied per call and would otherwise append an
            // EOS token to the end of every suffix fed in — turning what should be a
            // continuation into a sequence of terminated fragments. The module's BOS is
            // the opposite case: it is added once after construction or resetContext and
            // then suppressed, so incremental feeding cannot duplicate it, and the
            // per-call numBos in this config is ignored by the JNI layer entirely.
            .numEos(0)
            .build()

        running.generate(
            prompt,
            config,
            object : LlmCallback {
                override fun onResult(result: String) = onToken(result)

                override fun onStats(stats: String) {
                    this@NativeExecuTorchBridge.lastStats = stats
                }

                override fun onError(errorCode: Int, message: String) {
                    this@NativeExecuTorchBridge.lastError =
                        message.ifBlank { "ExecuTorch error $errorCode" }
                }
            },
        )

        lastError?.let { throw LlamaException(it) }
        return outcomeFrom(lastStats)
    }

    override fun resetContext() {
        module?.resetContext()
    }

    override fun stop() {
        module?.stop()
    }

    override fun close() {
        module?.close()
        module = null
    }

    /**
     * What the runtime measured, read out of the JSON it reports when a generation ends.
     *
     * Read by name and defensively rather than parsed into a type, because these field
     * names belong to ExecuTorch and change between releases. A field that is missing
     * leaves a zero, and [ExecuTorchEngine] falls back to wall-clock timing for the two
     * that matter — which is worse than the runtime's own numbers and better than a
     * number that quietly means something else.
     */
    private fun outcomeFrom(stats: String?): ExecuTorchOutcome {
        if (stats == null) return ExecuTorchOutcome(StopReason.END_OF_TURN)

        val promptTokens = stats.longField("prompt_tokens")
        val generatedTokens = stats.longField("generated_tokens")
        val inferenceStart = stats.longField("inference_start_ms")
        val promptEval = stats.longField("prompt_eval_end_ms")
        val inferenceEnd = stats.longField("inference_end_ms")

        val prefill = (promptEval - inferenceStart).coerceAtLeast(0)
        val decode = (inferenceEnd - promptEval).coerceAtLeast(0)
        val complete = promptEval > 0 && inferenceStart > 0

        return ExecuTorchOutcome(
            reason = StopReason.END_OF_TURN,
            promptTokens = promptTokens.toInt(),
            generatedTokens = generatedTokens.toInt(),
            prefillMs = if (complete) prefill else 0,
            decodeMs = if (complete && inferenceEnd > 0) decode else 0,
        )
    }

    /** The number stored against [name], or zero when this build of ExecuTorch omits it. */
    private fun String.longField(name: String): Long {
        val key = "\"$name\""
        val at = indexOf(key)
        if (at < 0) return 0
        val colon = indexOf(':', at + key.length)
        if (colon < 0) return 0
        return generateSequence(colon + 1) { it + 1 }
            .takeWhile { it < length }
            .dropWhile { this[it] == ' ' }
            .takeWhile { this[it].isDigit() }
            .map { this[it] }
            .joinToString("")
            .toLongOrNull() ?: 0
    }
}

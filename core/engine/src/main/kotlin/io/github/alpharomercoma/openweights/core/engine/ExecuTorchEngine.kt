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

import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ExecuTorchFileName
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.PromptTemplate
import io.github.alpharomercoma.openweights.core.common.model.PromptTemplates
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCallParser
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Runs a model that was compiled ahead of time, through ExecuTorch.
 *
 * The trade against [LlamaCppEngine] is worth stating plainly, because it is not a matter
 * of one being better written:
 *
 * - **Prefix reuse is unproven.** llama.cpp keeps a KV cache across turns and reports what
 *   it reused, so a follow-up demonstrably pays only for what changed. ExecuTorch does keep
 *   state — `LlmModule` has both `resetContext` and a prefill-without-generating call — but
 *   whether an ordinary generation continues from it, and how much of a turn that saves,
 *   has not been measured. Until it has, [GenerationStats.cachedTokens] reports zero,
 *   which is an admission that this engine cannot yet say rather than a claim that nothing
 *   was reused. On the long multi-turn traffic this app is measured against, the answer
 *   decides whether the engine is viable at all.
 * - **A curated catalogue.** A `.pte` is compiled on a desktop for one backend, and for an
 *   NPU one SoC, so models arrive because a build was run for them. That is the constraint
 *   this whole project exists to escape, which is why this engine is the second one and not
 *   the first.
 * - **No projector.** Attachments are llama.cpp's, via libmtmd.
 *
 * What it buys is the only path to an accelerator that has no ggml backend. See
 * `docs/research/mediatek-npu.md` for what that is measured to be worth.
 */
class ExecuTorchEngine(private val bridge: ExecuTorchBridge) : InferenceEngine {

    private var info: LoadedModelInfo? = null
    private var template: PromptTemplate? = null
    private var contextSize: Int = 0

    override val loadedModel: LoadedModelInfo? get() = info

    override suspend fun load(modelFile: File, params: ModelLoadParams, projectorFile: File?) {
        val tokenizer = tokenizerFor(modelFile)
            ?: throw LlamaException(
                "${modelFile.name} has no tokenizer beside it. A .pte carries a compiled " +
                    "graph and nothing that says how to tokenize for it, so both files " +
                    "have to be installed together.",
            )

        // Refuse rather than guess. A wrong template does not fail: the model answers, a
        // little worse, and its tool calls stop parsing, which reads as a bad model.
        val rendering = PromptTemplates.forModel(modelFile.name)
            ?: throw LlamaException(
                "No prompt template for ${modelFile.name}. This build can render: " +
                    PromptTemplates.known.joinToString(", ") + ".",
            )

        unload()
        contextSize = params.contextLength
        if (!bridge.load(
                modelFile.absolutePath,
                tokenizer.absolutePath,
                DEFAULT_TEMPERATURE,
                contextSize,
            )
        ) {
            throw LlamaException("ExecuTorch could not open ${modelFile.name}")
        }

        // The window is fixed when the model is exported — the runtime reads its own
        // `get_max_seq_len` and clamps to it — so what is asked for here is a ceiling
        // rather than a request, and a value above the model's own is quietly ignored.
        template = rendering
        info = LoadedModelInfo(
            description = modelFile.nameWithoutExtension,
            parameterCount = 0,
            sizeBytes = modelFile.length(),
            contextSize = contextSize,
            trainingContextSize = contextSize,
            layerCount = 0,
            contextUsed = 0,
            offloadedTo = "ExecuTorch",
            supportsThinking = true,
            supportsTools = true,
            supportsToolResults = true,
            modelPath = modelFile.absolutePath,
        )
    }

    override suspend fun unload() {
        bridge.close()
        info = null
        template = null
    }

    override fun chat(
        messages: List<ChatMessage>,
        params: SamplerParams,
        tools: List<ToolDefinition>,
    ): Flow<GenerationEvent> = flow {
        val rendering = template ?: throw LlamaException("No model loaded")
        val prompt = rendering.render(messages, tools, params.thinking)

        // ExecuTorch's runner continues from wherever it left off rather than matching a
        // prefix. `pos_` survives a generation, so sending the conversation again appends
        // a second copy behind the first: measured on device, turn one ended at pos_ 2047
        // and turn two tried to add 2068 more tokens into a 2048-token window and was
        // refused. llama.cpp would have recognised the shared prefix and charged for the
        // difference; this runtime has no such notion, so the honest thing is to start
        // from nothing and send the whole conversation, which is what the prompt above is.
        //
        // That makes every turn pay full prefill. Feeding only the new turn and letting
        // `pos_` carry would avoid it, and is the obvious next thing to measure — but it
        // is not free correctness: the cache would then hold assistant turns *with* their
        // reasoning, while Qwen3's template says to strip reasoning from everything before
        // the user's last question. The two cannot both be true, and picking wrong is the
        // kind of error that degrades answers without ever failing.
        bridge.resetContext()

        val reply = StringBuilder()
        val started = System.currentTimeMillis()
        var firstTokenAt = 0L

        // Zero means "no limit" to llama.cpp, which stops at the context edge on its own.
        // Here that becomes "as many as the window still has room for", and ExecuTorch
        // clamps it against the prompt for us. Passing the caller's number straight through
        // is only correct because the bridge takes *new* tokens; the runtime's simpler
        // entry point takes a total sequence length, where a 24-token reply budget behind a
        // 907-token prompt resolves to a negative allowance and refuses to generate.
        val budget = params.maxTokens.takeIf { it > 0 } ?: contextSize
        val outcome = bridge.generate(prompt, budget) { fragment ->
            if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
            reply.append(fragment)
        }

        // Everything from the end-of-turn marker onwards is the format leaking out, not
        // the answer. llama.cpp stops on these tokens because the GGUF names them;
        // ExecuTorch streams whatever it decodes, so every reply ended with a visible
        // `<|im_end|>` until this was here. Found on device, not by reading.
        val raw = rendering.stopMarkers
            .mapNotNull { marker -> reply.indexOf(marker).takeIf { it >= 0 } }
            .minOrNull()
            ?.let { reply.substring(0, it) }
            ?: reply.toString()
        val parsed = ToolCallParser.parse(raw)
        emit(
            GenerationEvent.Completed(
                reason = outcome.reason,
                stats = statsFor(outcome, started, firstTokenAt),
                content = parsed.text.withoutReasoning(),
                reasoning = raw.reasoning(),
                toolCalls = parsed.calls,
            ),
        )
    }.flowOn(Dispatchers.IO)

    override fun cancel() = bridge.stop()

    override suspend fun resetContext() = bridge.resetContext()

    /** Thread counts belong to the backend a `.pte` was compiled against, not to a call. */
    override suspend fun setThreads(generateThreads: Int, batchThreads: Int) = Unit

    override fun systemInfo(): String = "ExecuTorch"

    override fun computeDevices(): List<ComputeDevice> = emptyList()

    override fun close() {
        bridge.close()
        info = null
        template = null
    }

    /**
     * Time to first token is the prefill, and the rest is the decode.
     *
     * llama.cpp reports both from inside, having actually measured them. Here they are
     * split at the first fragment reaching us, which is the same boundary observed from
     * one step further out — and is exactly the wait the user feels.
     */
    private fun statsFor(
        outcome: ExecuTorchOutcome,
        started: Long,
        firstTokenAt: Long,
    ): GenerationStats {
        val finished = System.currentTimeMillis()
        val timeToFirst = if (firstTokenAt > 0) firstTokenAt - started else finished - started
        return GenerationStats(
            promptTokens = outcome.promptTokens,
            generatedTokens = outcome.generatedTokens,
            prefillMs = outcome.prefillMs.takeIf { it > 0 } ?: timeToFirst,
            decodeMs = outcome.decodeMs.takeIf { it > 0 } ?: (finished - started - timeToFirst),
            timeToFirstTokenMs = timeToFirst,
            contextUsed = 0,
            contextSize = contextSize,
            // Zero because this engine cannot yet say, not because nothing was reused.
            // See the note on prefix reuse in the class documentation.
            cachedTokens = 0,
        )
    }

    /**
     * The tokenizer exported alongside [model], by name.
     *
     * A sibling rather than a lookup, because the pairing has to survive a user moving
     * files around: `Qwen3-1.7B.pte` is answered by `Qwen3-1.7B.tokenizer.json`.
     */
    private fun tokenizerFor(model: File): File? =
        File(model.parentFile, ExecuTorchFileName.tokenizerNameFor(model.name))
            .takeIf { it.isFile }

    private fun String.reasoning(): String {
        val open = indexOf(THINK_OPEN)
        val close = indexOf(THINK_CLOSE)
        if (open < 0 || close < open) return ""
        return substring(open + THINK_OPEN.length, close).trim()
    }

    private fun String.withoutReasoning(): String {
        val close = lastIndexOf(THINK_CLOSE)
        if (close < 0) return this
        return substring(close + THINK_CLOSE.length).trim()
    }

    private companion object {
        const val THINK_OPEN = "<think>"
        const val THINK_CLOSE = "</think>"

        /**
         * ExecuTorch fixes temperature when the runner is built rather than per call, so
         * this is the value the model is opened with and [SamplerParams] cannot move it.
         */
        const val DEFAULT_TEMPERATURE = 0.8f
    }
}

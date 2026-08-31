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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
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
class ExecuTorchEngine(
    private val bridge: ExecuTorchBridge,
    /**
     * The sampling temperature the model is opened with. A constructor parameter rather
     * than a [SamplerParams] field because ExecuTorch fixes it when the runner is built,
     * not per call; zero means greedy, which is what a reproducible evaluation loads.
     */
    private val temperature: Float = DEFAULT_TEMPERATURE,
) : InferenceEngine {

    private var info: LoadedModelInfo? = null
    private var template: PromptTemplate? = null
    private var contextSize: Int = 0

    /**
     * The text the runtime's KV cache currently holds, prompt and reply together.
     *
     * Kept as text rather than as a token count because that is what can be compared: the
     * next turn is an extension of this one exactly when its rendered prompt starts with
     * this string. Cleared whenever the cache is, since a stale value would claim the
     * runtime holds something it does not and skip feeding it.
     */
    private var fedText: String = ""

    /**
     * How many tokens the runtime is holding, counted rather than guessed.
     *
     * The runtime reports what each call *gave* it, so the conversation's length is the
     * running sum of every prompt and reply fed since the cache was last cleared. That sum
     * is what a turn reusing the cache did not have to re-read, which is exactly what
     * [GenerationStats.cachedTokens] means.
     */
    private var heldTokens: Int = 0

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
                temperature,
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
            // The template is the authority: a family whose format cannot express tools
            // must not be offered them, or the agent loop waits for calls that cannot come.
            supportsThinking = rendering.supportsThinking,
            supportsTools = rendering.supportsTools,
            supportsToolResults = rendering.supportsTools,
            modelPath = modelFile.absolutePath,
        )
    }

    override suspend fun unload() {
        bridge.close()
        fedText = ""
        heldTokens = 0

        info = null
        template = null
    }

    override fun chat(
        messages: List<ChatMessage>,
        params: SamplerParams,
        tools: List<ToolDefinition>,
    ): Flow<GenerationEvent> = channelFlow {
        val rendering = template ?: throw LlamaException("No model loaded")
        val prompt = rendering.render(messages, tools, params.thinking)

        // What the runtime already holds, and whether this turn extends it.
        //
        // ExecuTorch continues rather than matching a prefix: `pos_` survives a generation
        // and `generate` appends wherever it left off. That makes re-sending a conversation
        // a bug — measured, turn one ended at pos_ 2047 and turn two was refused for
        // appending 2068 more into a 2048 window — but it also means the cache is worth
        // keeping when the new prompt genuinely begins with what is in it.
        //
        // Comparing the rendered text is the whole test, and it is exact. A template that
        // re-renders an earlier turn differently produces a prompt that is not an extension,
        // the comparison fails, and the turn starts from nothing. No knowledge of *why* it
        // changed is needed here, which matters because the reasons are not obvious:
        // Qwen3 drops `<think>` from assistant turns once a newer user question arrives, the
        // tool list lives at the very front and this app withdraws it when a turn's budget
        // is spent, and consecutive tool results collapse into one block whose terminator
        // moves when a second result lands. Each rewrites text that has already been fed.
        // An exact hit would leave nothing to send, and this runtime rejects an empty
        // prompt outright — it only accepts one after a separate native prefill call that
        // this bridge never makes. Requiring new text keeps that unreachable.
        val extending = fedText.isNotEmpty() &&
            prompt.startsWith(fedText) &&
            prompt.length > fedText.length
        val reused = if (extending) heldTokens else 0
        val fresh = if (extending) {
            prompt.substring(fedText.length)
        } else {
            bridge.resetContext()
            fedText = ""
            heldTokens = 0

            prompt
        }

        val reply = StreamedReply(rendering)
        val started = System.currentTimeMillis()
        var firstTokenAt = 0L

        // Zero means "no limit" to llama.cpp, which stops at the context edge on its own.
        // Here that becomes "as much as the window still has room for". Passing the number
        // straight through is only correct because the bridge counts *new* tokens; the
        // runtime's simpler entry point counts total sequence length, where a 24-token
        // reply budget behind a 907-token prompt resolved to 1141 and ignored the budget.
        val budget = params.maxTokens.takeIf { it > 0 } ?: contextSize

        // Anything thrown below leaves the runtime's position advanced by however much it
        // managed to prefill and decode, which no longer matches anything recorded. The
        // cache is unusable rather than merely unknown, so it is dropped: a retry that
        // trusted the old record would send the same suffix at an already-advanced position
        // and duplicate it.
        var produced = 0
        val outcome = try {
            bridge.generate(fresh, budget) { fragment ->
                if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                reply.accept(fragment)?.let { trySend(GenerationEvent.Token(it)) }
                // The budget is enforced here as well as passed down, because passing it
                // down turned out to be advisory: on-device, a 768-token budget behind a
                // 405-token SmolLM3 prompt resolved to 1643 in the runner and the model
                // wrote until the window was full. One callback is one token, so counting
                // callbacks is exact, and stop() is the same lever the markers pull.
                produced += 1
                if (reply.endedCleanly || produced >= budget) bridge.stop()
            }
        } catch (failure: Throwable) {
            fedText = ""
            heldTokens = 0
            runCatching { bridge.resetContext() }
            throw failure
        }

        // Whatever was held back in case it grew into a marker still belongs to the reply
        // when nothing came of it. Without this flush the caller's streamed text ends short
        // of the completed text, and since the app stores what it streamed, its history
        // would no longer match what was fed.
        reply.flush()?.let { trySend(GenerationEvent.Token(it)) }

        // What the runtime is *committed* to, which is not what it produced. A sampled token
        // only enters the KV cache when it is fed back in to produce the next one, so the
        // token that ended generation never got there: stopping at `<|im_end|>` leaves the
        // cache holding everything before it and not the marker itself. Recording the marker
        // would make the next turn skip feeding it, and the two turns would run together
        // with no end-of-turn between them.
        //
        // The same is true of a reply that ran out of budget, except there the uncommitted
        // token is ordinary text this code cannot identify by character position. So that
        // case gives up reuse entirely rather than guess: an empty record forces the next
        // turn to start over, which is slow and correct.
        if (reply.endedCleanly) {
            fedText = prompt + reply.answer
            heldTokens = reused + outcome.promptTokens + outcome.generatedTokens
        } else {
            fedText = ""
            heldTokens = 0
        }

        val raw = reply.answer
        val parsed = ToolCallParser.parse(raw)
        send(
            GenerationEvent.Completed(
                reason = outcome.reason,
                stats = statsFor(outcome, started, firstTokenAt, reused, prompt),
                content = parsed.text.withoutReasoning(),
                reasoning = raw.reasoning(),
                toolCalls = parsed.calls,
            ),
        )
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    override fun cancel() = bridge.stop()

    override suspend fun resetContext() {
        bridge.resetContext()
        fedText = ""
        heldTokens = 0
    }

    /** Thread counts belong to the backend a `.pte` was compiled against, not to a call. */
    override suspend fun setThreads(generateThreads: Int, batchThreads: Int) = Unit

    override fun systemInfo(): String = "ExecuTorch"

    override fun computeDevices(): List<ComputeDevice> = emptyList()

    override fun close() {
        bridge.close()
        fedText = ""
        heldTokens = 0

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
        reused: Int,
        prompt: String,
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
            // What the runtime kept rather than what it re-read. It reports the tokens
            // it was *given*, which on an extending turn is only the new text, so the rest
            // of the conversation is the difference between that and the window in use.
            // Zero on a turn that started from nothing, which is the honest answer there.
            cachedTokens = reused,
            // The opener can carry a thinking block the reply continues from — Qwen3
            // closes an empty one when reasoning is switched off — and it is in the prompt
            // and never in the reply, so stored history has to have it put back.
            //
            // Worth knowing that this does not rescue the cache. The template drops that
            // block from history however it is stored, so a turn generated with reasoning
            // off can never be extended: switching reasoning *off* is what costs the cache
            // here, which is the opposite of what it sounds like.
            thinkingPrefilled = prompt.endsWith(THINK_CLOSE + "\n\n"),
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

/**
 * A reply arriving in fragments, and the question of how much of it is safe to show.
 *
 * Separate from the engine because the arithmetic is fiddly and entirely about text.
 * Fragments do not arrive on marker boundaries, so `<|im_end|>` reaches the callback in
 * pieces; streaming each piece as it lands puts `<|i` on screen and then takes it away.
 * Nothing that could still grow into a marker is released until it either becomes one or
 * cannot.
 */
private class StreamedReply(private val template: PromptTemplate) {
    private val text = StringBuilder()
    private var shown = 0
    private var ends = -1

    /** True once the model produced an end-of-turn marker rather than merely stopping. */
    val endedCleanly: Boolean get() = ends >= 0

    /** The reply without the marker or anything after it. */
    val answer: String get() = text.take(if (ends >= 0) ends else text.length).toString()

    /** Adds [fragment], returning any text that has become safe to show. */
    fun accept(fragment: String): String? {
        text.append(fragment)
        if (ends < 0) {
            ends = template.stopMarkers
                .mapNotNull { marker -> text.indexOf(marker).takeIf { at -> at >= 0 } }
                .minOrNull() ?: -1
        }
        val safe = if (ends >= 0) ends else text.length - template.danglingMarkerLength(text)
        if (safe <= shown) return null
        return text.substring(shown, safe).also { shown = safe }
    }

    /** Text withheld in case it became a marker, once it is known that it did not. */
    fun flush(): String? {
        val finish = if (ends >= 0) ends else text.length
        if (finish <= shown) return null
        return text.substring(shown, finish).also { shown = finish }
    }
}

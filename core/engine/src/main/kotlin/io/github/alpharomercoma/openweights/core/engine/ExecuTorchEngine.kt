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

import android.util.Log
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ExecuTorchFileName
import io.github.alpharomercoma.openweights.core.common.model.MediaKind
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.PromptTemplate
import io.github.alpharomercoma.openweights.core.common.model.PromptTemplates
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCallParser
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.common.model.VisionSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    /** How a picture on disk becomes the encoder's square; swapped out in tests. */
    private val reader: PictureReader = AndroidPictureReader(),
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
     * The most characters one call into the runtime may carry, prefill or generate.
     *
     * The exporter bounds the token input at `max_seq_len - 1` and the runtime chunks a
     * long prompt at `max_seq_len`, so a single call of 2048 tokens fails on a 2048/32k
     * export with "Attempted to resize a bounded tensor with a maximum capacity of 2047
     * elements to 2048 elements" (Poco X8 Pro, 2026-09-08: every research step's prompt
     * with the tool prefix was refused, read as a full window, and retried without tools).
     * Characters are the only measure this engine has before tokenizing, and one token
     * per character is the worst case, so a bound in characters below the token bound is
     * safe for any text.
     */
    private var callChars: Int = GENERATE_TAIL_CHARS

    /**
     * What this turn fed through prefill before generate: pieces of the prompt too long
     * for one call (see [feedAhead]), by characters and by the wall clock.
     *
     * Kept so the turn's stats can say what the turn cost. The runtime's own figures cover
     * the generate call alone, and a turn whose head went in as pieces used to report only
     * its tail: the prefill rate of the last four hundred tokens, and the two thousand
     * before them counted as cached, which they were not. Zeroed at the start of every turn.
     */
    private var fedAheadChars: Int = 0
    private var fedAheadMs: Long = 0L

    /** The text the turn's generate call was given, whose token count the runtime reports. */
    private var tailChars: Int = 0

    /**
     * The text the runtime's KV cache currently holds, prompt and reply together.
     *
     * Kept as text rather than as a token count because that is what can be compared: the
     * next turn is an extension of this one exactly when its rendered prompt starts with
     * this string. Cleared whenever the cache is, since a stale value would claim the
     * runtime holds something it does not and skip feeding it.
     */
    private var fedText: String = ""

    /** Whether the open file reads pictures, which is the file's to say and the template's to feed. */
    private var multimodal: Boolean = false

    /** The family's BOS, when the tokenizer beside the model will not write it; else empty. */
    private var bosPrefix: String = ""

    /**
     * How many tokens the runtime is holding, counted rather than guessed.
     *
     * The runtime reports what each call *gave* it, so the conversation's length is the
     * running sum of every prompt and reply fed since the cache was last cleared. That sum
     * is what a turn reusing the cache did not have to re-read, which is exactly what
     * [GenerationStats.cachedTokens] means.
     */
    private var heldTokens: Int = 0

    /** Set by [cancel]; read between warm pieces, which is where a warm can stop. */
    @Volatile
    private var warmStopped = false

    /**
     * One thing at a time on the runtime, and on the record of what it holds.
     *
     * The llama.cpp engine has one native thread and everything queues behind it. This one
     * had none: a turn, a warm and a reset could each run on whichever IO thread the
     * caller was on, and the app does overlap them, a fold's summary turn against the warm
     * queued behind the load. Two writers to [fedText] and the runtime's own position at
     * once is text fed twice into the cache. So every entry that touches either takes this
     * lock, held for the whole of a turn's stream; [cancel] does not, because its job is to
     * end what holds it.
     */
    private val turns = Mutex()

    // What the runtime holds right now, not what it held at load. The turn runner reads
    // this before every round to decide how much tool output fits; a constant zero told
    // it the whole window was free after every pass.
    override val loadedModel: LoadedModelInfo? get() = info?.copy(contextUsed = heldTokens)

    override suspend fun load(modelFile: File, params: ModelLoadParams, projectorFile: File?) {
        // Off the caller's thread. The chat loads from the main dispatcher, and this maps
        // the whole file and allocates the cache for the exported window: on a 1.8 GB
        // export at 32k that is seconds, and the phone showed "not responding" every five
        // seconds until it was done (2026-09-07, LFM2.5 2.6B). llama.cpp's engine has
        // always hopped to its own thread here; this one did not.
        turns.withLock { withContext(Dispatchers.IO) { loadLocked(modelFile, params) } }
    }

    private fun loadLocked(modelFile: File, params: ModelLoadParams) {
        val tokenizer = tokenizerFor(modelFile)
            ?: refuse(
                "${modelFile.name} has no tokenizer beside it. A .pte carries a compiled " +
                    "graph and nothing that says how to tokenize for it, so both files " +
                    "have to be installed together.",
            )

        // Refuse rather than guess. A wrong template does not fail: the model answers, a
        // little worse, and its tool calls stop parsing, which reads as a bad model.
        val rendering = PromptTemplates.forModel(modelFile.name)
            ?: refuse(
                "No prompt template for ${modelFile.name}. This build can render: " +
                    PromptTemplates.known.joinToString(", ") + ".",
            )

        closeModel()
        // The window is the file's, not the preference's. A preference above it is clamped
        // by the runtime anyway, and reporting the preference upstream made every budget
        // in the app wrong by the difference.
        // A file that cannot be probed is not refused here; the runner's own open below is
        // the authority on whether it can be run, and says so with a message.
        val facts = runCatching { bridge.probe(modelFile.absolutePath) }
            .getOrDefault(ExportFacts(contextLength = null, hasVision = false))
        val exported = facts.contextLength
        callChars = facts.callChars()
        contextSize = when {
            exported == null -> params.contextLength
            params.contextLength <= 0 -> exported
            else -> minOf(params.contextLength, exported)
        }
        // Pictures need both halves: an encoder in the file and a template that knows
        // the square it takes. A vision export of a family this app cannot feed opens as
        // text, which is honest and still useful.
        multimodal = facts.hasVision && rendering.vision != null
        // A model whose tokenizer will not write its BOS gets it from the template instead.
        bosPrefix = rendering.bosToken
            ?.takeUnless { bridge.tokenizerAddsBos(tokenizer.absolutePath) }
            .orEmpty()
        // The multimodal runner in the AAR this app ships aborts the whole process, not the
        // call, when an export lacks the window method it reads first (measured with an
        // older exporter's SmolVLM2: "Required metadata method get_max_seq_len not found").
        // A file that cannot say its window is refused before the runner sees it.
        if (multimodal && exported == null) {
            refuse(
                "${modelFile.name} was exported without the metadata this app's ExecuTorch " +
                    "runtime needs to read pictures. Ask its publisher for a current export.",
            )
        }
        if (!bridge.load(
                modelFile.absolutePath,
                tokenizer.absolutePath,
                // The publisher's own setting for the vision export: sampled at the text
                // default it turns generic and repetitive (Software Mansion's model notes,
                // and their runner's defaults for LFM2.5-VL).
                if (multimodal) VISION_TEMPERATURE else temperature,
                contextSize,
                multimodal,
            )
        ) {
            refuse("ExecuTorch could not open ${modelFile.name}")
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
            trainingContextSize = exported ?: contextSize,
            layerCount = 0,
            contextUsed = 0,
            offloadedTo = "ExecuTorch",
            mediaSupport = MediaSupport(vision = multimodal),
            // The template is the authority: a family whose format cannot express tools
            // must not be offered them, or the agent loop waits for calls that cannot come.
            supportsThinking = rendering.supportsThinking,
            supportsTools = rendering.supportsTools,
            supportsToolResults = rendering.supportsTools,
            modelPath = modelFile.absolutePath,
        )
    }

    /** The character bound for one runtime call: see [callChars]. */
    private fun ExportFacts.callChars(): Int =
        minOf(GENERATE_TAIL_CHARS, (prefillLength ?: Int.MAX_VALUE) - 1).coerceAtLeast(1)

    /** A load refused for a reason the caller can show. */
    private fun refuse(message: String): Nothing = throw LlamaException(message)

    override suspend fun unload() = turns.withLock { withContext(Dispatchers.IO) { closeModel() } }

    /** Under [turns]: the callers that already hold it cannot take it twice. */
    private fun closeModel() {
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
        turns.withLock { stream(messages, params, tools) }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    /** One turn, from render to the completed event, with [turns] held throughout. */
    private suspend fun ProducerScope<GenerationEvent>.stream(
        messages: List<ChatMessage>,
        params: SamplerParams,
        tools: List<ToolDefinition>,
    ) {
        val rendering = template ?: throw LlamaException("No model loaded")
        cancelRequested = false
        val pictures = if (multimodal) messages.pictures() else emptyList()
        val prompt = render(
            messages.markingPictures(pictures.isNotEmpty()),
            tools,
            params.thinking,
        )
        val started = System.currentTimeMillis()
        fedAheadChars = 0
        fedAheadMs = 0L
        val (fresh, reused) = openTurn(prompt, pictures, rendering, started) ?: return

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
        //
        // A turn with pictures starts from nothing and leaves nothing to extend: the
        // pictures sit in the cache as embeddings, which the text record here cannot
        // describe, so the next turn re-feeds them rather than trusting a prefix match.

        val reply = StreamedReply(rendering)
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
        val discipline = StopDiscipline(budget)
        // Not cleared here. A Stop that landed while the prompt was being rendered or the
        // context reset has already set it, and the runtime's own stop flag it also raised
        // is wiped when the token loop starts; the flag is the only record left. The
        // callback reads it on the first token and re-issues the stop. It is cleared at
        // the start of the turn instead, before anything a Stop could be aimed at.
        tailChars = fresh.length
        val outcome = try {
            bridge.generate(fresh, budget) { fragment ->
                if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                reply.accept(fragment)?.let { trySend(GenerationEvent.Token(it)) }
                // Asked on every token rather than once from cancel(), because the
                // runtime clears its stop flag when the token loop starts: a Stop that
                // lands during the prefill ahead of it is erased, and a collector that
                // has gone away has nobody left to call cancel() at all. Left running,
                // the loop holds the module's lock until the window fills, and the next
                // turn or a model switch waits behind it.
                if (stopNow(discipline, fragment, reply, isClosedForSend)) bridge.stop()
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
        if (reply.endedCleanly && pictures.isEmpty()) {
            fedText = prompt + reply.answer
            heldTokens = reused + fedAheadTokens(outcome) + outcome.promptTokens +
                outcome.generatedTokens
        } else if (reply.endedCleanly) {
            // Held, but not extendable: the runtime counts the picture's positions in its
            // prompt figure, so the meter is right even though the text record is empty.
            fedText = ""
            heldTokens = outcome.promptTokens + outcome.generatedTokens
        } else {
            fedText = ""
            heldTokens = 0
        }

        val raw = reply.answer
        val parsed = ToolCallParser.parse(raw)
        send(
            GenerationEvent.Completed(
                reason = reasonFor(reply, discipline, outcome.reason),
                stats = statsFor(outcome, started, firstTokenAt, reused, prompt),
                content = parsed.text.withoutReasoning(),
                reasoning = raw.reasoning(),
                toolCalls = parsed.calls,
            ),
        )
    }

    override fun cancel() {
        warmStopped = true
        cancelRequested = true
        // The runtime's stop is safe against a running generation and nothing else: a
        // picture inside the encoder is left to finish, and the flag above ends the turn
        // before the next one (codex QA).
        if (!feedingPictures) bridge.stop()
    }

    /** True while a picture is inside the encoder, when the runtime must not be stopped. */
    @Volatile
    private var feedingPictures = false

    /**
     * Set by [cancel] and read by the token callback, which re-issues the stop. The
     * runtime's flag alone is not enough: see the callback in [chat].
     */
    @Volatile
    private var cancelRequested = false

    /**
     * Whether this token is the one to stop on: the user asked, the collector has gone,
     * or the discipline's own budget and rut rules say so. The first two are asked on
     * every token because the runtime clears its stop flag when the loop starts.
     */
    private fun stopNow(
        discipline: StopDiscipline,
        fragment: String,
        reply: StreamedReply,
        collectorGone: Boolean,
    ): Boolean = cancelRequested ||
        collectorGone ||
        discipline.shouldStop(fragment, reply.endedCleanly)

    /**
     * Why a generation ended, decided here because the runtime's own answer is always
     * "end of turn": its callback reports statistics, not why the loop stopped, and the
     * bridge can only pass that on. A pass cut for budget, caught in a rut, or stopped by
     * the user therefore read as a finished one, so a tool call sitting in a truncated
     * reply ran and the turn loop kept a stopped turn's history as if it were whole.
     */
    private fun reasonFor(
        reply: StreamedReply,
        discipline: StopDiscipline,
        runtime: StopReason,
    ): StopReason = when {
        cancelRequested -> StopReason.CANCELLED
        reply.endedCleanly -> StopReason.END_OF_TURN
        discipline.cut -> StopReason.MAX_TOKENS
        else -> runtime
    }

    /**
     * Reads [messages] into the runtime's cache, ahead of anybody asking anything.
     *
     * The llama runtime warms by prefilling and can abort mid-batch; this one warms on
     * the runner's prefill-only entry, which cannot be stopped once called — [stop]
     * gates the token loop, and a prefill has no token loop. So the text goes in pieces,
     * cut at whitespace, and cancellation is checked between them: the piece is the
     * interrupt latency. Everything fed stays useful when interrupted — the cache is
     * append-only and the record grows piece by piece — so the turn that interrupted, or
     * the next warm, extends whatever was already read. The pieces re-tokenize at their
     * cut points, which is the same thing every turn boundary in this engine already
     * does to the stream.
     *
     * The contract is "make the cache exactly this prompt": equal reads nothing, an
     * extension feeds the difference, and anything else — including a cache *longer*
     * than the target, which a forward-only runtime cannot serve — resets and refeeds.
     * That last arm means a reopened or branched conversation re-reads in the
     * background here where llama would roll back; slower, correct, and off the user's
     * clock.
     *
     * [snapshot] and [store] have no meaning on this runtime: it cannot roll back, so
     * there is nothing a snapshot could restore, and it cannot serialize state, so
     * nothing outlives the process. A cold start pays one background head read.
     */
    override suspend fun warm(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        params: SamplerParams,
        snapshot: Boolean,
        store: String?,
    ): WarmResult? = turns.withLock {
        warmLocked(messages, tools, params)
    }

    /**
     * [beginTurn], with its two ways of not getting there.
     *
     * A Stop that lands while the pictures are going in ends the turn as a cancellation,
     * with nothing generated and nothing left in the cache; any other failure clears the
     * cache too, because half a prompt in it is worse than none (the next turn would
     * extend it), and is rethrown for the caller to show. Null means the turn is over.
     */
    @Suppress("SwallowedException")
    private suspend fun ProducerScope<GenerationEvent>.openTurn(
        prompt: String,
        pictures: List<MessagePart.File>,
        rendering: PromptTemplate,
        started: Long,
    ): Pair<String, Int>? = try {
        beginTurn(prompt, pictures, rendering)
    } catch (stopped: StoppedWhileFeeding) {
        forgetHeld()
        send(
            GenerationEvent.Completed(
                reason = StopReason.CANCELLED,
                stats = statsFor(ExecuTorchOutcome(StopReason.CANCELLED), started, 0L, 0, prompt),
                content = "",
                reasoning = "",
                toolCalls = emptyList(),
            ),
        )
        null
    } catch (failure: Throwable) {
        forgetHeld()
        throw failure
    }

    private fun forgetHeld() {
        fedText = ""
        heldTokens = 0
        runCatching { bridge.resetContext() }
    }

    /**
     * What this turn hands to generate, and how many tokens the runtime already held.
     *
     * The cache is extended when the new prompt begins with what was fed before and the
     * turn carries no pictures; otherwise it is cleared and the whole prompt is fresh,
     * with the pictures fed on the way so that only the closing text remains.
     */
    private fun beginTurn(
        prompt: String,
        pictures: List<MessagePart.File>,
        rendering: PromptTemplate,
    ): Pair<String, Int> {
        val extending = pictures.isEmpty() &&
            fedText.isNotEmpty() &&
            prompt.startsWith(fedText) &&
            prompt.length > fedText.length
        if (extending) return feedAhead(prompt.substring(fedText.length), heldTokens)
        bridge.resetContext()
        fedText = ""
        heldTokens = 0
        val fresh = if (pictures.isEmpty()) prompt else feedPictures(prompt, pictures, rendering)
        return feedAhead(fresh, 0)
    }

    /**
     * Feeds all but the last [callChars] of [fresh] through prefill, in warm pieces, and
     * returns the tail for generate with the tokens fed ahead added to [held].
     *
     * The runtime's own chunking cannot be trusted with a long prompt (see [callChars]),
     * and its prefill entry cannot be stopped once called, so the pieces are the warm's
     * and a Stop is read between them. Everything fed stays in the record: the cache is
     * append-only, so the tail generate extends it exactly as a warmed turn does.
     */
    private fun feedAhead(fresh: String, held: Int): Pair<String, Int> {
        var rest = fresh
        while (rest.length > callChars) {
            if (cancelRequested) throw StoppedWhileFeeding()
            val piece = warmPiece(rest, callChars)
            val before = System.currentTimeMillis()
            bridge.prefill(piece)
            fedAheadMs += System.currentTimeMillis() - before
            fedAheadChars += piece.length
            fedText += piece
            heldTokens += (piece.length / WARM_CHARS_PER_TOKEN).coerceAtLeast(1)
            rest = rest.substring(piece.length)
        }
        // The pieces are this turn's work, not the cache's: they are reported as prompt
        // tokens by [statsFor], so what is returned as reused is only what was held before.
        return rest to held
    }

    /**
     * Feeds every text-and-picture pair ahead of the final text, which the generate
     * call takes.
     *
     * The prompt arrives with one [PICTURE_MARKER] where each picture belongs. Around each
     * the export expects its own brackets, the same ones its processor writes: the text
     * before, then `<|image_start|>`, then the picture as embeddings, then `<|image_end|>`
     * leading the text after. What is returned is that last text, brackets included.
     */
    private fun feedPictures(
        fresh: String,
        pictures: List<MessagePart.File>,
        rendering: PromptTemplate,
    ): String {
        val spec = rendering.vision ?: throw LlamaException("This model cannot read pictures")
        val segments = fresh.split(PICTURE_MARKER)
        refuseUnlessPicturesFit(segments, pictures, spec, fresh)
        var carried = ""
        pictures.forEachIndexed { index, picture ->
            // Each picture is a second or two of encoder; a Stop is honoured between them.
            if (cancelRequested) throw StoppedWhileFeeding()
            bridge.prefill(carried + segments[index] + spec.before)
            feedingPictures = true
            try {
                bridge.prefillImage(
                    readPicture(picture, spec),
                    spec.side,
                    spec.side,
                    Letterbox.CHANNELS,
                )
            } finally {
                feedingPictures = false
            }
            carried = spec.after
        }
        return carried + segments.last()
    }

    /** The picture on the encoder's square, in the range the encoder takes. */
    private fun readPicture(picture: MessagePart.File, spec: VisionSpec): FloatArray {
        val raw = reader.read(picture.path, spec.side, spec.fit)
            ?: throw LlamaException(
                "Could not read ${picture.name ?: picture.path.substringAfterLast('/')}",
            )
        return spec.pixels.applyTo(raw)
    }

    /**
     * Refused before the first encoder call rather than discovered at the last: eight
     * pictures at 256 positions each are a 2048 window with no room for a word.
     */
    private fun refuseUnlessPicturesFit(
        segments: List<String>,
        pictures: List<MessagePart.File>,
        spec: VisionSpec,
        fresh: String,
    ) {
        if (segments.size != pictures.size + 1) {
            throw LlamaException(
                "${segments.size - 1} picture markers for ${pictures.size} pictures",
            )
        }
        val visual = pictures.size * spec.tokens
        val brackets = pictures.size * (spec.before.length + spec.after.length)
        val text = (fresh.length + brackets) / WARM_CHARS_PER_TOKEN
        if (visual + text >= contextSize) {
            throw ContextWindowExceededException(
                "${pictures.size} pictures take $visual of this model's $contextSize positions, " +
                    "leaving no room for the conversation. Send fewer pictures at a time.",
            )
        }
    }

    /** A Stop that landed between pictures, before anything was generated. */
    private class StoppedWhileFeeding : LlamaException("Stopped while feeding the prompt")

    private fun List<ChatMessage>.pictures(): List<MessagePart.File> =
        flatMap { message -> message.files.filter { it.kind == MediaKind.IMAGE } }

    /**
     * The messages with each picture replaced by [PICTURE_MARKER] in its text, so the
     * template renders the conversation with the pictures in place; untouched when the
     * turn carries none, so a marker never reaches a model that would read it aloud.
     */
    private fun List<ChatMessage>.markingPictures(withPictures: Boolean): List<ChatMessage> =
        if (!withPictures) {
            this
        } else {
            // Every message is re-rendered, not only those with pictures: a marker typed
            // into an earlier text-only message would otherwise be counted as a picture.
            map { message ->
                ChatMessage(
                    message.role,
                    listOf(MessagePart.Text(message.textWithPictureMarkers())),
                )
            }
        }

    /**
     * The message's text with a marker where each picture sits, and nothing else added.
     *
     * The export's processor writes the image brackets straight into the text with no
     * whitespace around them, so none is added here; the shared media rendering puts a
     * newline on each side, which the model would read as text and which moves every
     * later position. Files that are not pictures are left out, since this runtime has
     * no way in for them, and a marker typed into the text is removed so the count of
     * markers stays the count of pictures.
     */
    private fun ChatMessage.textWithPictureMarkers(): String = buildString {
        parts.forEach { part ->
            when (part) {
                is MessagePart.Text -> append(part.text.replace(PICTURE_MARKER, ""))
                is MessagePart.File -> if (part.kind == MediaKind.IMAGE) append(PICTURE_MARKER)
            }
        }
    }

    private suspend fun warmLocked(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        params: SamplerParams,
    ): WarmResult? = withContext(Dispatchers.IO) {
        val rendering = template ?: return@withContext null
        // The llama warm renders without the generation prompt so its text prefixes any
        // future turn; these templates cannot be asked that, so it is computed instead:
        // the common prefix of this conversation rendered as a prompt and the same
        // conversation with one more user turn is the history text alone — everything up
        // to where the assistant opener and the next turn diverge. A family that folds
        // the system text into the first user turn diverges early and warms almost
        // nothing, which is the correct no-op for it.
        // A conversation with pictures is fed at the turn, embeddings and all; there is
        // no text-only warm of it that the turn could extend.
        if (multimodal && messages.pictures().isNotEmpty()) return@withContext null
        val full = render(messages, tools, params.thinking)
        val probed = render(
            messages + ChatMessage.text(ChatRole.USER, WARM_PROBE),
            tools,
            params.thinking,
        )
        val prompt = full.commonPrefixWith(probed)
        if (prompt.isEmpty()) return@withContext null
        if (prompt == fedText) {
            return@withContext WarmResult(
                warmedTokens = 0,
                reusedTokens = heldTokens,
                prefillMs = 0,
                snapshotBytes = 0,
            )
        }

        val extending = fedText.isNotEmpty() && prompt.startsWith(fedText)
        val reused = if (extending) heldTokens else 0
        var fresh = if (extending) {
            prompt.substring(fedText.length)
        } else {
            bridge.resetContext()
            fedText = ""
            heldTokens = 0
            prompt
        }

        warmStopped = false
        val started = System.currentTimeMillis()
        var warmed = 0
        try {
            while (fresh.isNotEmpty() && !warmStopped) {
                val piece = warmPiece(fresh, callChars)
                bridge.prefill(piece)
                fedText += piece
                val tokens = (piece.length / WARM_CHARS_PER_TOKEN).coerceAtLeast(1)
                heldTokens += tokens
                warmed += tokens
                fresh = fresh.substring(piece.length)
            }
        } catch (failure: LlamaException) {
            // The runtime's position no longer matches anything recorded; an extension
            // record that might be wrong is worse than none. Same discipline as a
            // failed generate. Logged rather than raised, because a warm is an
            // optimization and its failure already costs exactly what it saves.
            Log.w(TAG, "warm prefill failed; the next turn starts cold", failure)
            fedText = ""
            heldTokens = 0
            runCatching { bridge.resetContext() }
            return@withContext null
        }
        WarmResult(
            warmedTokens = warmed,
            reusedTokens = reused,
            prefillMs = System.currentTimeMillis() - started,
            snapshotBytes = 0,
        )
    }

    /**
     * The next piece to feed: at most [WARM_PIECE_CHARS] and never more than [limit],
     * preferring to end at a line break, then at a space, so the cut re-tokenizes no more
     * oddly than it must.
     */
    private fun warmPiece(text: String, limit: Int): String {
        val most = minOf(WARM_PIECE_CHARS, limit)
        if (text.length <= most) return text
        val window = text.substring(0, most)
        val newline = window.lastIndexOf('\n')
        if (newline > 0) return window.substring(0, newline + 1)
        val space = window.lastIndexOf(' ')
        if (space > 0) return window.substring(0, space + 1)
        return window
    }

    override suspend fun resetContext() = turns.withLock {
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
     * The turn's cost, from the runtime's figures for the generate call plus what this
     * engine fed ahead of it.
     *
     * The runtime measures the generate call from inside: `prompt_eval_end_ms` less
     * `inference_start_ms` is its prefill, `inference_end_ms` less `prompt_eval_end_ms`
     * its decode, and both are what it actually spent. Two corrections are this side's.
     *
     * The first token is the prefill's. The runner samples one token at the end of the
     * prompt and hands it to the callback before its decode loop starts, and
     * `generated_tokens` counts only the loop's steps, so the reply the reader saw is one
     * token longer than the runtime's count. Reported as the reader saw it, and that also
     * makes the decode rate exact: [GenerationStats.decodeTokensPerSecond] divides the
     * tokens after the first by the decode time, and the loop's steps are exactly those.
     * Without the correction a thirty-token reply read as twenty-nine at three per cent
     * under its real rate, and the same reply on llama.cpp, which counts its first token,
     * did not, so the two runtimes disagreed about identical work.
     *
     * The pieces fed ahead are prompt, not cache. A prompt longer than one call may carry
     * goes in as prefill pieces first (see [feedAhead]), and the runtime's figures know
     * nothing of them. Their time is measured around each call. Their token count is not
     * measured, because the Java binding exposes no tokenizer, so it is the piece text at
     * the rate the generate call reported for the tail it did count, which is the same
     * text in the same template, or four characters a token when there was no tail. The
     * one estimate in these stats, and named as one; the alternative was two thousand
     * tokens of prefill reported as a cache hit.
     *
     * Time to first token is wall clock from the start of the turn to the first fragment,
     * pieces included, because that is the wait the user felt.
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
        val fedAhead = fedAheadTokens(outcome)
        return GenerationStats(
            promptTokens = outcome.promptTokens + fedAhead,
            generatedTokens = outcome.generatedTokens + if (firstTokenAt > 0) 1 else 0,
            // The runtime's prefill covers the generate call; the pieces before it were
            // timed here. When the runtime reported nothing, the wait to the first token is
            // the whole prefill, pieces and all.
            prefillMs = outcome.prefillMs.takeIf { it > 0 }?.plus(fedAheadMs) ?: timeToFirst,
            decodeMs = outcome.decodeMs.takeIf { it > 0 } ?: (finished - started - timeToFirst),
            timeToFirstTokenMs = timeToFirst,
            contextUsed = heldTokens,
            contextSize = contextSize,
            // What the runtime kept rather than what it re-read: what was held before this
            // turn began. The pieces fed this turn are in promptTokens above, not here.
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
     * How many tokens this turn's fed-ahead pieces came to, at the rate the generate
     * call measured for its own tail. See [statsFor] for why this is the one estimate.
     */
    private fun fedAheadTokens(outcome: ExecuTorchOutcome): Int {
        if (fedAheadChars == 0) return 0
        val tail = tailChars
        val charsPerToken = if (tail > 0 && outcome.promptTokens > 0) {
            tail.toDouble() / outcome.promptTokens
        } else {
            WARM_CHARS_PER_TOKEN.toDouble()
        }
        return (fedAheadChars / charsPerToken).toInt().coerceAtLeast(1)
    }

    /**
     * The tokenizer exported alongside [model], by name.
     *
     * A sibling rather than a lookup, because the pairing has to survive a user moving
     * files around: `Qwen3-1.7B.pte` is answered by `Qwen3-1.7B.tokenizer.json`.
     */
    /**
     * The prompt as the runtime should see it: the template's text, led by the family's BOS
     * when the tokenizer beside this model will not add one. Every render goes through here
     * so the cache's record of what was fed and the next prompt agree on the prefix.
     */
    private fun render(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        thinking: Boolean,
    ): String {
        val rendering = template ?: throw LlamaException("No model loaded")
        return bosPrefix + rendering.render(messages, tools, thinking)
    }

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
        // A closer with no opener before it is text, not the end of a thinking block: a
        // model describing the tag would otherwise lose everything it said up to there.
        if (close < 0 || indexOf(THINK_OPEN) !in 0..<close) return this
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

        /** What the publisher samples LFM2.5-VL at; the text default makes it ramble. */
        const val VISION_TEMPERATURE = 0.1f
    }
}

/**
 * When a generation has to be cut, decided a callback at a time.
 *
 * Three reasons, all enforced here because the runtime enforces none of them. The budget
 * passed down turned out to be advisory: on-device, a 768-token budget behind a 405-token
 * SmolLM3 prompt resolved to 1643 in the runner and the model wrote until the window was
 * full; one callback is one token, so counting callbacks is exact. And this runtime's
 * sampler has no repetition penalty, so a greedy model in a rut emits one token forever —
 * measured: SmolLM3 filled its budget with a control character after a tool result. The
 * same token thirty-two times in a row is a rut, never prose; whole-word loops stay the
 * model's own.
 */
private class StopDiscipline(private val budget: Int) {
    private var produced = 0
    private var lastFragment = ""
    private var repeats = 0

    /** True once this cut the generation short: budget spent or a rut, not a clean end. */
    var cut = false
        private set

    fun shouldStop(fragment: String, endedCleanly: Boolean): Boolean {
        produced += 1
        repeats = if (fragment == lastFragment) repeats + 1 else 0
        lastFragment = fragment
        if (endedCleanly) return true
        if (produced >= budget || repeats >= MAX_TOKEN_RUT) cut = true
        return cut
    }

    private companion object {
        /** Identical consecutive tokens before generation is cut as degenerate. */
        const val MAX_TOKEN_RUT = 32
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

/** One warm piece: about two hundred tokens, which is the interrupt latency in text. */
private const val WARM_PIECE_CHARS = 800

/**
 * The most characters handed to one generate call, under the 2047-token input bound of
 * every export this app ships or publishes even at one token per character. Anything
 * beyond it is fed ahead in warm pieces. See `callChars`.
 */
private const val GENERATE_TAIL_CHARS = 1600

/** Stands in for a picture in the rendered prompt until the picture is fed in its place. */
private const val PICTURE_MARKER = "\u0000picture\u0000"

/** The app-wide rough estimate; the runtime reports no token count for a prefill. */
private const val WARM_CHARS_PER_TOKEN = 4

/** Any user text: only the render's shape matters, never the probe's content. */
private const val WARM_PROBE = "x"

private const val TAG = "OpenWeights"

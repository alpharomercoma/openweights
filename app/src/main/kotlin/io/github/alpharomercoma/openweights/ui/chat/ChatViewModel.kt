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

package io.github.alpharomercoma.openweights.ui.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.common.context.Compaction
import io.github.alpharomercoma.openweights.core.common.model.AssistantReply
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.GgufFileName
import io.github.alpharomercoma.openweights.core.common.model.MediaKind
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.common.model.withoutToolMarkup
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.data.Offload
import io.github.alpharomercoma.openweights.core.data.db.MessageEntity
import io.github.alpharomercoma.openweights.core.data.decodeAttachments
import io.github.alpharomercoma.openweights.core.data.layersFor
import io.github.alpharomercoma.openweights.core.device.ThermalLevel
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.GenerationStats
import io.github.alpharomercoma.openweights.core.engine.MediaSupport
import io.github.alpharomercoma.openweights.core.engine.StopReason
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.model.StagedDocument
import io.github.alpharomercoma.openweights.ui.ReplyNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/**
 * A turn as shown in the transcript, with the measurements taken while producing it.
 *
 * [text] is the raw model output; [reasoning] and [answer] are the split view of it, so the
 * UI never has to parse and the raw form stays available for regeneration and export.
 */
data class TranscriptEntry(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val reasoning: String? = null,
    val answer: String = text,
    val isReasoningInProgress: Boolean = false,
    val reasoningMs: Long? = null,
    val tokensPerSecond: Double? = null,
    val timeToFirstTokenMs: Long? = null,
    val generatedTokens: Int? = null,
    val isStreaming: Boolean = false,
    /** Set on the first entry that survives a compaction, so the fold is visible. */
    val compactionNote: String? = null,
    /** Files sent with this turn, shown above its text. */
    val attachments: List<MessagePart.File> = emptyList(),
    /** Everything the model said and did before the answer, in order. */
    val blocks: List<TurnBlock> = emptyList(),
    /** Wall clock from send to finished, which is what the wait actually felt like. */
    val totalMillis: Long? = null,
)

/** A past conversation, as shown in the drawer. */
data class ConversationSummary(
    val id: Long,
    val title: String,
    val modelName: String?,
    val updatedAt: Long,
)

/**
 * What the runtime is doing.
 *
 * Named states rather than a spinner, because on a phone these are minutes apart and they
 * mean different things: reading a prompt with four video frames in it is not the same
 * wait as generating, and a phone that has quietly halved its thread count looks exactly
 * like a slow model unless something says so.
 *
 * This is the part of the interface no cloud assistant has, because no cloud assistant is
 * running on hardware the user can feel getting warm.
 */
enum class RuntimeState(val label: String) {
    NO_MODEL("no model"),
    LOADING("loading weights"),
    READY("ready"),
    READING("reading the prompt"),
    GENERATING("generating"),
    COMPACTING("folding earlier turns"),
    THROTTLED("cooling down"),
    ;

    /** True while the runtime is busy, which is when the state means anything. */
    val isBusy: Boolean get() = this != READY && this != NO_MODEL
}

/** Everything the chat screen renders. */
data class ChatUiState(
    val modelName: String? = null,
    val modelQuantization: String? = null,
    val isLoadingModel: Boolean = false,
    val isGenerating: Boolean = false,
    val transcript: List<TranscriptEntry> = emptyList(),
    val contextUsed: Int = 0,
    val contextSize: Int = 0,
    val error: String? = null,
    val isCompacting: Boolean = false,
    val compaction: Compaction? = null,
    val conversations: List<ConversationSummary> = emptyList(),
    val activeConversationId: Long? = null,
    val preferences: ModelPreferences = ModelPreferences(),
    /** What the loaded model can read. All false without a projector. */
    val mediaSupport: MediaSupport = MediaSupport(),
    /** True when this model's chat template understands being told whether to think. */
    val supportsThinking: Boolean = false,
    /**
     * True when this model's chat template renders tool definitions.
     *
     * A template that does not drops them silently, so the model is told it has tools it
     * has no way to call and answers "I should use the search tool" forever. Asked of the
     * template at load, not guessed from the name.
     */
    val supportsTools: Boolean = false,
    /**
     * True when this model's chat template does something with the effort setting.
     *
     * Measured at load by rendering the template twice, so the control appears only where
     * moving it changes the prompt.
     */
    val supportsReasoningEffort: Boolean = false,
    /** True when at least one tool is switched on in Tools. */
    val toolsAvailable: Boolean = false,
    /** True when this device has a backend other than the CPU to offload layers to. */
    val hasGpu: Boolean = false,
    /**
     * How hot the device is, sampled while a reply is being written.
     *
     * The one reading no hosted assistant can show, because there the hardware getting warm
     * is somebody else's. This is the four step level the system publishes, which is what
     * the scheduler acts on when it slows the phone down, so it is the one to colour by and
     * to throttle on. [deviceCelsius] is the number beside it.
     */
    val thermal: ThermalLevel = ThermalLevel.NONE,
    /**
     * The device temperature in degrees, or null where the platform will not say.
     *
     * The level above is what decides throttling; this is the number a person asked for
     * when they asked how hot their phone was.
     */
    val deviceCelsius: Float? = null,
    /** Attachments staged in the composer, not yet sent. */
    val staged: List<MessagePart.File> = emptyList(),
    /**
     * A text document staged in the composer, not yet sent.
     *
     * Separate from [staged] because it is a different thing wearing the same word. Media
     * needs a model that can see or hear and a file on disk for the projector to open; a
     * document needs neither, because it becomes part of the question. That is why this one
     * is offered whatever model is loaded.
     */
    val stagedDocument: StagedDocument? = null,
    /** True while a picked file is being copied in. */
    val isAttaching: Boolean = false,
    /** The compute device the engine is actually running on, e.g. `CPU`. */
    val backend: String? = null,
    /**
     * True while the phone is hot enough that the thread plan has been cut back.
     *
     * Only meaningful while generating. A device cools while idle, and the reading is only
     * taken around a reply, so claiming "cooling down" on an idle screen would be showing
     * a measurement minutes out of date.
     */
    val isThrottled: Boolean = false,
    /**
     * How much rope the model gets.
     *
     * Auto by default. Asking per call sounds safer and is not: a model that searches
     * three times to answer one question produces three prompts, and a user who taps
     * through three prompts stops reading the fourth. The tools that ship cannot write
     * anything or spend anything, so the honest default is to let them run and to say
     * clearly in the transcript what ran.
     */
    val mode: AgentMode = AgentMode.AUTO,
    /** A tool waiting for the user to allow it. Null when nothing is waiting. */
    val pendingApproval: ToolCall? = null,
) {
    /**
     * The runtime's current state, in the order it matters.
     *
     * Throttling outranks everything: it explains a slowness the other states cannot, and
     * a user who does not know the phone is hot has no reason to put it down.
     */
    val runtimeState: RuntimeState
        get() = when {
            isThrottled && isGenerating -> RuntimeState.THROTTLED
            isLoadingModel -> RuntimeState.LOADING
            modelName == null -> RuntimeState.NO_MODEL
            isCompacting -> RuntimeState.COMPACTING
            // No text yet means the prompt is still being read, which with an attachment
            // is most of the wait, and is the one part a spinner cannot explain.
            isGenerating && transcript.lastOrNull()?.text.isNullOrEmpty() -> RuntimeState.READING
            isGenerating -> RuntimeState.GENERATING
            else -> RuntimeState.READY
        }

    /**
     * What is loaded, in one line: quantization, compute device, context window.
     *
     * Shown whenever the runtime is idle. It is the answer to "what am I actually talking
     * to", which for this app changes with every download and is otherwise invisible.
     */
    val runtimeIdentity: String
        get() = listOfNotNull(
            // Not the quantization: it is already the tail of the model name directly
            // above, and repeating it wastes the only line that can say something new.
            backend,
            contextSize.takeIf { it > 0 }?.let { "$it ctx" },
        ).joinToString(" · ")

    val canSend: Boolean get() = modelName != null && !isGenerating && !isLoadingModel

    /** How full the model's context window is, as a fraction. */
    val contextFraction: Float
        get() = if (contextSize > 0) contextUsed.toFloat() / contextSize else 0f
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val runtime: ModelRuntime,
    private val compactor: ConversationCompactor,
    private val staging: Staging,
    private val writer: ChatWriter,
    private val turns: TurnRunner,
    private val notifier: ReplyNotifier,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    /** Completed by the approval buttons, so the agent can wait on a human. */
    private var approval: CompletableDeferred<Boolean>? = null
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private var thermalJob: Job? = null
    private var nextEntryId = 0L

    /**
     * True only while a turn is inside the engine.
     *
     * Distinct from [ChatUiState.isGenerating], which the composer sets the moment it is
     * tapped so that two quick taps cannot both start a turn. Compaction resets the KV
     * cache, so what it has to know is whether anything is decoding into it, and those two
     * facts stop agreeing for the whole window between the tap and the first token.
     */
    private var isDecoding = false

    /** Held for the length of a load, so two of them cannot interleave. */
    private val loadMutex = Mutex()

    /** Counts load requests, so a queued one can tell it has been superseded. */
    private var loadRequest = 0L

    /**
     * The filename settings are stored against.
     *
     * The full name, not the stem: two repositories can both ship `model.gguf`, and
     * sharing one system prompt between them would be silent cross-contamination.
     */
    private var preferencesKey: String? = null

    /** The row this conversation is being written to, created lazily on the first message. */
    private var conversationId: Long? = null
        set(value) {
            field = value
            _uiState.update { it.copy(activeConversationId = value) }
            // Remembered, and never forgotten. Android reclaims a process holding a model in
            // memory sooner than most, and coming back to a blank screen with the
            // conversation still in the database reads as having lost it. SavedStateHandle
            // is the right shelf: it survives being killed for memory and does not survive
            // being swiped away, which is exactly the distinction that matters here.
            if (value != null) savedState[LAST_CONVERSATION] = value
        }

    /**
     * The conversation to reopen once a model is up, read once when the view model is built.
     *
     * Consumed by the first load, which on a cold start is the one the screen asks for. It
     * cannot fire twice, so switching model afterwards still starts a fresh chat.
     */
    private var restoring: Long? = savedState[LAST_CONVERSATION]

    init {
        viewModelScope.launch {
            writer.conversations().collect { rows ->
                _uiState.update { state ->
                    state.copy(
                        conversations = rows.map {
                            ConversationSummary(it.id, it.title, it.modelName, it.updatedAt)
                        },
                    )
                }
            }
        }
    }

    /**
     * True once a model has been loaded or is loading, so the screen can ask for the
     * default model on first composition without reloading it after a rotation.
     */
    val hasModel: Boolean
        get() = _uiState.value.modelName != null || _uiState.value.isLoadingModel

    /** Loads the model last chosen, or whichever is on disk if there is no choice yet. */
    fun loadDefaultModel() {
        runtime.preferredModel()?.let(::loadModel)
    }

    /** Loads a GGUF file from disk. */
    /**
     * Loads a model.
     *
     * @param keepConversation carry the open chat over to the new model instead of
     * starting a fresh one. The transcript is text, so it survives the swap; the KV cache
     * does not, and is rebuilt from that text on the next reply.
     */
    fun loadModel(modelFile: File, contextLength: Int? = null, keepConversation: Boolean = false) {
        // A generation in flight writes into the transcript, so it has to finish before the
        // model under it is replaced, not merely be asked to stop.
        stop()
        // Claimed on the caller's thread, so the ordering matches the taps rather than the
        // order coroutines happen to start in.
        val request = ++loadRequest
        viewModelScope.launch {
            generationJob?.join()
            // One load at a time. Two in flight would both write the identity of whichever
            // model they finished with, and the engine would be holding neither.
            loadMutex.withLock {
                // A load that was superseded while queueing is not worth the seconds it
                // costs: the user has already asked for a different model.
                if (request != loadRequest) return@withLock
                performLoad(modelFile, contextLength, keepConversation)
            }
        }
    }

    /**
     * Drops everything that described the model being replaced.
     *
     * Before the attempt, not after: the engine frees what it held as it loads, so from
     * that moment the old name, context size and capabilities describe nothing. The staged
     * attachments go too, whether the load succeeds or fails, because they were picked for
     * a model that is no longer there.
     */
    private suspend fun forgetLoadedModel() {
        val abandoned = _uiState.value.staged
        _uiState.update {
            it.copy(
                modelName = null,
                modelQuantization = null,
                backend = null,
                contextSize = 0,
                contextUsed = 0,
                mediaSupport = MediaSupport(),
                supportsThinking = false,
                supportsTools = false,
                supportsReasoningEffort = false,
                staged = emptyList(),
            )
        }
        if (abandoned.isNotEmpty()) staging.discard(abandoned)
    }

    /**
     * How to load this model, including which processor gets its layers.
     *
     * Auto's answer is a measurement rather than a setting, and the measurement changes as
     * the model is used, so it is resolved per load. That is also the only moment llama.cpp
     * will accept it.
     */
    private suspend fun loadParamsFor(
        modelFile: File,
        preferences: ModelPreferences,
        contextLength: Int?,
    ): ModelLoadParams {
        val (prompted, generated) = writer.inOrder { turnShape(modelFile.nameWithoutExtension) }
        val layers = Offload.fromName(preferences.offload)
            .layersFor(runtime.hasGpu(), prompted, generated)
        return contextLength
            ?.let { ModelLoadParams(contextLength = it, gpuLayers = layers) }
            ?: preferences.toLoadParams(layers)
    }

    private suspend fun performLoad(
        modelFile: File,
        contextLength: Int?,
        keepConversation: Boolean,
    ) {
        _uiState.update { it.copy(isLoadingModel = true, error = null) }
        val preferences = runtime.settingsFor(modelFile.name)
        val loadParams = loadParamsFor(modelFile, preferences, contextLength)

        val projector = runtime.projectorFor(modelFile)

        forgetLoadedModel()

        runCatching {
            runtime.load(modelFile, loadParams, projector)
        }.onSuccess {
            // The cache belonged to the old weights. Clearing it makes the next reply
            // re-read the transcript, which is what carries the conversation across.
            runtime.resetContext()
            runtime.rememberChoice(modelFile)
            if (!keepConversation) conversationId = null
            preferencesKey = modelFile.name
            val info = runtime.loadedModel
            val support = info?.mediaSupport ?: MediaSupport()
            _uiState.update {
                it.copy(
                    isLoadingModel = false,
                    backend = runtime.backendName(),
                    hasGpu = runtime.hasGpu(),
                    modelName = modelFile.nameWithoutExtension,
                    // The filename's own quantization, not llama's verbose description:
                    // "Q4_K_M" beside the compute device and context window reads as a
                    // spec line, "lfm2 1.2B Q4_K - Medium" reads as a sentence.
                    modelQuantization = GgufFileName.quantization(modelFile.name),
                    contextSize = info?.contextSize ?: 0,
                    contextUsed = info?.contextUsed ?: 0,
                    preferences = preferences,
                    transcript = if (keepConversation) it.transcript else emptyList(),
                    compaction = if (keepConversation) it.compaction else null,
                    mediaSupport = support,
                    // Two hurdles, and a model has to clear both. The template must render
                    // differently when told not to think, and the weights must not have
                    // been caught ignoring that in a previous reply.
                    supportsThinking = info?.supportsThinking == true &&
                        !runtime.ignoresThinkingSwitch(modelFile.name),
                    supportsTools = info?.supportsTools == true,
                    supportsReasoningEffort = info?.supportsReasoningEffort == true,
                    error = if (keepConversation) {
                        it.transcript.unreadableWarning(support)
                    } else {
                        null
                    },
                )
            }
            // Only ever on the startup load, so switching model still starts fresh.
            if (!keepConversation) {
                restoring?.let { id ->
                    restoring = null
                    openConversation(id)
                }
            }

            if (keepConversation) {
                conversationId?.let { id ->
                    writer.inOrder { setModel(id, modelFile.nameWithoutExtension) }
                }
            }
        }.onFailure { failure ->
            _uiState.update { it.copy(isLoadingModel = false, error = failure.userMessage()) }
        }
    }

    /**
     * Sends a message, or says it could not.
     *
     * Returns false when the turn was refused, so the composer can keep what was typed.
     * It used to return silently and the composer cleared regardless, so a question asked
     * while the weights were still being mapped, which is the first thing anyone does on a
     * cold start, vanished with nothing said about it.
     */
    fun send(prompt: String): Boolean {
        val typed = prompt.trim()
        val staged = _uiState.value.staged
        val document = _uiState.value.stagedDocument
        // An attachment on its own is a complete message: "what is this?" is implied.
        val nothingToSend = typed.isEmpty() && staged.isEmpty() && document == null
        if (nothingToSend) return false
        if (!_uiState.value.canSend) {
            _uiState.value.refusalReason()?.let { why ->
                _uiState.update { it.copy(error = why) }
            }
            return false
        }

        // The document becomes part of what was asked, rather than something carried
        // alongside it. That keeps one string as the message: what is shown, what is
        // stored, and what the model reads are the same, which is what makes a reopened
        // conversation send the model the same thing it saw the first time.
        val text = document?.let { it.asPrompt() + typed }?.trim() ?: typed

        // isGenerating is claimed here, before any suspending work: two quick taps would
        // otherwise both pass canSend, create two conversations, and race the engine.
        _uiState.update { state ->
            state.copy(
                transcript = state.transcript +
                    entry(ChatRole.USER, text).copy(attachments = staged),
                isGenerating = true,
                staged = emptyList(),
                stagedDocument = null,
                error = null,
            )
        }

        // Awaited before generating: a fast reply could otherwise finish before the row
        // exists and be dropped, taking its usage record with it.
        //
        // Tracked as the generation job from the first line, because until generate() runs
        // there was nothing for Stop to cancel. The write is short but it is not instant,
        // it can wait on the mutex behind a compaction, and isGenerating is already true:
        // Stop in that window did nothing at all, and the turn it appeared to cancel then
        // started against whatever state had replaced it.
        val send = viewModelScope.launch {
            // Answered either way. What the user typed is on screen and worth a reply even
            // if the disk would not take it, so a failure here is reported rather than
            // thrown: silence left a message that was there for the session and gone on
            // reopening, with the turn generating against it as though it were durable.
            reportingFailure {
                writer.inOrder {
                    val title = text.ifEmpty { staged.firstOrNull()?.describe() ?: "Attachment" }
                    val id = conversationId
                        ?: startConversation(title, _uiState.value.modelName)
                            .also { conversationId = it }
                    addMessage(id, ChatRole.USER.wireName, text, attachments = staged)
                }
            }
            generate()
        }
        generationJob = send
        // On completion rather than in a catch inside the block, because a job cancelled
        // before its body starts never runs the catch at all. generate() is what normally
        // hands the busy state back, and if it was never reached nothing else will.
        send.invokeOnCompletion { cause ->
            if (cause != null && generationJob === send) {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
        return true
    }

    /**
     * Stages a picked file for the next message.
     *
     * Copied in immediately rather than at send time, so the thumbnail appears at once and
     * the picker's read permission is used while it is still granted.
     */
    fun attach(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAttaching = true) }
            // In a finally: a throw here would otherwise leave the attach button spinning
            // with no way back to it.
            val staged = try {
                staging.file(uri, _uiState.value.mediaSupport)
            } finally {
                _uiState.update { it.copy(isAttaching = false) }
            }
            _uiState.update { it.after(staged) }
        }
    }

    /** Removes a staged attachment and deletes the copy that was made of it. */
    fun removeStaged(attachment: MessagePart.File) {
        _uiState.update { it.copy(staged = it.staged - attachment) }
        viewModelScope.launch { staging.discard(attachment) }
    }

    /**
     * Stages a text document to be read into the next question, or clears the staged one.
     *
     * One function for both because they are one decision, made twice: what document, if
     * any, goes with the next message. Null is that decision reaching "none".
     *
     * Offered whatever model is loaded, which is the point of it: reading a document takes
     * no projector and no vision, so this is the one attachment a plain text model can use.
     *
     * How much of it fits is decided here, from the window the loaded model actually has,
     * because a document that overruns the context does not produce a worse answer, it
     * produces a failed decode.
     */
    fun stageDocument(uri: Uri?) {
        if (uri == null) {
            _uiState.update { it.copy(stagedDocument = null) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isAttaching = true) }
            val staged = try {
                staging.document(uri, documentBudget(_uiState.value))
            } finally {
                _uiState.update { it.copy(isAttaching = false) }
            }
            _uiState.update { it.after(staged) }
        }
    }

    /**
     * Discards the last model reply and asks again.
     *
     * The engine reuses the cached prompt prefix, so a regeneration costs only new tokens.
     */
    fun regenerate() {
        val state = _uiState.value
        if (state.isGenerating || state.transcript.none { it.role == ChatRole.ASSISTANT }) return

        _uiState.update {
            it.copy(
                transcript = it.transcript.dropLastWhile { entry ->
                    entry.role == ChatRole.ASSISTANT
                },
                error = null,
                isGenerating = true,
            )
        }

        viewModelScope.launch {
            // Storage has to lose the same replies the screen just lost. Without this the
            // conversation reopens with the discarded reply and the new one both in it.
            // Under the same lock as the writes, so the read cannot happen before the
            // reply being discarded has been inserted.
            writer.inOrder {
                conversationId?.let { id ->
                    val stored = messages(id)
                    // The same rule the transcript used: the trailing run of replies, which
                    // starts after the last thing that was not one.
                    val firstDiscarded =
                        stored.indexOfLast { it.role != ChatRole.ASSISTANT.wireName } + 1
                    stored.getOrNull(firstDiscarded)?.let { deleteFrom(id, it.id) }
                }
            }
            generate()
        }
    }

    /**
     * Keeps the thermal reading current for as long as a reply is being written.
     *
     * Sampled while working rather than once at the end: a phone warms up over the course
     * of a long answer, and a reading taken when it finishes describes a device that has
     * already begun cooling.
     *
     * Off the main dispatcher, so the wait between readings is a real wait. On the main one
     * it is whatever dispatcher the caller installed, and under a test scheduler a delay
     * costs nothing and takes no time: a loop of them is an infinite loop that always has
     * one more task ready, so `advanceUntilIdle` never returns. That is not a hypothetical.
     * It hung the suite for an hour at a time, and each run that was killed left a worker
     * behind spinning a core, which is why every build on this machine got slower until the
     * cause was found. Reading a hardware sensor was never main-thread work anyway.
     */
    private fun startThermalSampling() {
        thermalJob?.cancel()
        thermalJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val level = runtime.thermalLevel()
                val degrees = runtime.thermalCelsius()
                _uiState.update { it.copy(thermal = level, deviceCelsius = degrees) }
                delay(THERMAL_SAMPLE_MS)
            }
        }
    }

    private fun generate() {
        startThermalSampling()

        val job = viewModelScope.launch {
            // Folded before the turn as well as after it. Compaction only ever ran at the
            // end, so a conversation already past the threshold started its next turn
            // against the wall, and what the user saw was the turn failing rather than the
            // history being folded. Cheap when there is nothing to fold.
            compactIfNeeded()

            // Read here rather than held: the user can switch a tool off in another tab
            // between one question and the next, and the instruction has to follow.
            _uiState.update { it.copy(toolsAvailable = turns.hasEnabledTools()) }

            val state = _uiState.value
            val conversation = state.engineMessages()
            if (conversation.isEmpty()) {
                // Callers claim the busy state before this point to close the double-tap
                // window, so nothing to send has to give it back.
                _uiState.update { it.copy(isGenerating = false) }
                return@launch
            }

            _uiState.update { current ->
                current.copy(
                    transcript = current.transcript +
                        entry(ChatRole.ASSISTANT, "").copy(isStreaming = true),
                    isGenerating = true,
                )
            }

            val turnStartedAt = System.currentTimeMillis()
            var lastFrameAt = 0L
            var reasoningEndedAt: Long? = null
            var produced = ""

            // What the turn will write down, replaced by each pass that completes, so what
            // survives is the pass the turn ended on rather than the one that asked for a
            // tool. Null until a pass completes at all, which is what tells the difference
            // between a turn that answered and one that was stopped before it could.
            var settled: Pair<String, GenerationStats>? = null

            val listener = object : TurnListener {
                override fun onText(raw: String) {
                    produced = raw
                    // Not every token: re-parsing the whole reply and rebuilding its
                    // markdown tree costs more than a frame, so publishing per token on a
                    // phone that is also running the model means the list never settles.
                    val now = System.currentTimeMillis()
                    if (now - lastFrameAt < STREAM_FRAME_MS) return
                    lastFrameAt = now

                    val parsed = parseAssistantReply(raw)
                    if (reasoningEndedAt == null &&
                        parsed.reasoning != null &&
                        !parsed.isReasoningInProgress
                    ) {
                        reasoningEndedAt = System.currentTimeMillis()
                    }
                    applyStreamedText(raw, parsed, reasoningEndedAt, turnStartedAt)
                }

                override fun onPass(event: GenerationEvent.Completed, raw: String) {
                    settled = applyCompletion(event, raw) to event.stats
                }

                override fun onSteps(steps: List<AgentStep>) =
                    updateLastEntry { it.copy(blocks = it.blocks + steps.map(TurnBlock::Step)) }

                override fun onIntermediate(text: String) =
                    updateLastEntry { it.copy(blocks = it.blocks + TurnBlock.Said(text)) }

                override fun onNextPass() {
                    // Room for the next pass under the same entry, which is what makes a
                    // turn with tools in it read as one answer rather than several.
                    produced = ""
                    lastFrameAt = 0L
                    updateLastEntry { it.copy(isStreaming = true, text = "", answer = "") }
                }

                override suspend fun onApproval(call: ToolCall): Boolean = askUser(call)
            }

            try {
                // Re-planned per reply: the phone is a different machine hot than cold,
                // and a count chosen at load time is wrong by the third long answer.
                // Inside the try because it suspends, so Stop can land here too.
                if (!applyThreadPlan()) return@launch

                isDecoding = true
                produced = turns.run(
                    conversation = conversation,
                    params = state.preferences.toSamplerParams(),
                    mode = _uiState.value.mode,
                    // Offering a tool to a template that cannot render one wastes the
                    // context it takes up and leaves the model describing what it would
                    // do if it could.
                    withTools = state.supportsTools && state.toolsAvailable,
                    listener = listener,
                )
                // Here, so a turn that used a tool is written down once. Skipped by both
                // catches below, where finishInterrupted writes what was produced instead.
                // Blank is not written at all: an empty row reopens as an empty bubble and
                // is resent as an empty assistant turn, which some templates refuse.
                settled
                    ?.takeIf { (text, _) -> text.isNotBlank() }
                    ?.let { (text, stats) -> persistReply(text, stats) }
            } catch (cancellation: CancellationException) {
                // Stop was pressed. What arrived before it is real output the user watched
                // being written, so it is kept and stored like any other reply.
                finishInterrupted(produced)
                _uiState.update { it.copy(isGenerating = false, pendingApproval = null) }
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                finishInterrupted(produced)
                // The cache is whatever the failed decode left behind, and the next turn
                // would be built on top of it and fail the same way. Clearing it costs one
                // re-read of the transcript, which is the text still on screen, so nothing
                // the user can see is lost by it.
                runCatching { runtime.resetContext() }
                _uiState.update { it.copy(error = failure.userMessage()) }
            } finally {
                // However the turn ended, including Stop rethrowing above: the cache is
                // idle again and the next fold is allowed to reset it.
                isDecoding = false
            }

            settleTurn(produced, turnStartedAt)
        }
        generationJob = job
        job.invokeOnCompletion(::releaseTurn)
    }

    /**
     * Gives back what the turn was holding, and folds if the context now needs it.
     *
     * Its own function because it runs the same way whether the turn ended in an answer or
     * in an error, and because the last act of a turn is the easiest thing to lose sight of
     * at the bottom of a long one.
     */
    private suspend fun settleTurn(raw: String, turnStartedAt: Long) {
        // A stream that ends without a completion event, which happens if the engine's
        // channel closes under it, would otherwise leave the entry streaming forever.
        if (_uiState.value.transcript.lastOrNull()?.isStreaming == true) {
            finishInterrupted(raw)
        }

        _uiState.update { it.droppingEmptyReply() ?: it }
        updateLastEntry { it.copy(totalMillis = System.currentTimeMillis() - turnStartedAt) }
        // Re-read rather than leave the last reading standing: a phone that has cooled
        // between replies should stop claiming it is hot.
        _uiState.update {
            it.copy(
                isGenerating = false,
                isThrottled = runtime.isThrottling(),
                pendingApproval = null,
            )
        }
        compactIfNeeded()
        notifier.notifyReply(_uiState.value.transcript.lastOrNull()?.answer.orEmpty())
    }

    /**
     * Gives back everything a turn was holding, however it ended.
     *
     * The try inside [generate] does not open until compaction has finished, and folding a
     * long history asks the model for a summary, which is seconds. Stop pressed in that
     * window threw straight out of the coroutine, past every catch, with isGenerating still
     * claimed from the tap: the composer stayed locked behind a Stop button that had
     * nothing left to cancel. Anything ending this job abnormally has to give the busy
     * state back, wherever it happened.
     *
     * The sampler is stopped either way, because two of the three ways a turn ends never
     * reach the last line of the body: Stop rethrows its cancellation and a failed decode
     * returns through the catch, and either one left the thermal API being read every few
     * seconds for the rest of the process.
     */
    private fun releaseTurn(cause: Throwable?) {
        thermalJob?.cancel()
        if (cause != null) {
            _uiState.update { it.copy(isGenerating = false, pendingApproval = null) }
        }
    }

    /**
     * Waits for the user to allow one tool.
     *
     * Suspends the agent rather than polling, so Stop is never blocked by a question
     * nobody answered: cancelling the turn cancels this too.
     */
    private suspend fun askUser(call: ToolCall): Boolean {
        val pending = CompletableDeferred<Boolean>()
        approval = pending
        _uiState.update { it.copy(pendingApproval = call) }
        return try {
            pending.await()
        } finally {
            approval = null
            _uiState.update { it.copy(pendingApproval = null) }
        }
    }

    /** Answers the pending tool question. */
    fun resolveApproval(allowed: Boolean) {
        approval?.complete(allowed)
    }

    /** Switches how much the model may do without being asked. */
    fun setMode(mode: AgentMode) = _uiState.update { it.copy(mode = mode) }

    /**
     * Re-plans threads for this reply, and says whether to go ahead.
     *
     * The decision to stop rather than to stop slightly less lives in the runtime; what
     * lives here is what the user is told when it does.
     */
    private suspend fun applyThreadPlan(): Boolean {
        _uiState.update { it.copy(isThrottled = runtime.isThrottling()) }
        if (runtime.planThreads()) return true

        _uiState.update {
            it.copy(
                isGenerating = false,
                error = "The phone is too hot to keep generating. It will work again " +
                    "once it cools down.",
            )
        }
        updateLastEntry { it.copy(isStreaming = false) }
        return false
    }

    /**
     * Folds older turns into a summary once the context window gets tight.
     *
     * Running out of context is what kills a long conversation, and it always happens
     * mid-answer. Compacting between turns instead means the chat simply continues; the
     * full transcript stays on screen and only what is sent to the model shrinks.
     */
    private suspend fun compactIfNeeded(force: Boolean = false) {
        val state = _uiState.value
        if (state.isCompacting) return
        if (!force && !compactor.shouldCompact(state)) return

        // Captured before suspending: compaction runs the model, which takes long enough for
        // the user to open another chat. Applying chat A's summary to chat B would corrupt
        // both, so the result is discarded unless it still belongs where it started.
        val startedIn = conversationId

        _uiState.update { it.copy(isCompacting = true) }
        val compaction = try {
            compactor.compact(state, engineIsDecoding = isDecoding)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            // Never fatal. This runs before the try inside generate() opens, in a coroutine
            // with no exception handler on its scope, so anything thrown here reached the
            // platform's default handler and took the process with it. Folding is an
            // optimisation; the conversation is still on screen and the turn can still be
            // attempted, so a failure to fold is worth saying and nothing more.
            _uiState.update { it.copy(error = failure.userMessage()) }
            null
        } finally {
            // In a finally: a model that fails mid-summary would otherwise leave "folding
            // earlier turns" on screen forever, and block every later compaction.
            _uiState.update { it.copy(isCompacting = false) }
        }

        if (startedIn != conversationId) return

        if (compaction != null) {
            // Without this a compacted chat reopens with no summary and re-sends the whole
            // transcript, which walks straight back into the context wall it just escaped.
            startedIn?.let { id ->
                writer.inOrder {
                    saveCompaction(id, compaction.summary, compaction.foldedThroughIndex)
                }
            }
        }

        _uiState.update { current ->
            if (compaction == null) {
                current
            } else {
                val folded = current.copy(
                    compaction = compaction,
                    transcript = current.transcript.mapIndexed { index, entry ->
                        if (index == compaction.foldedThroughIndex + 1) {
                            entry.copy(compactionNote = COMPACTION_NOTE)
                        } else {
                            entry
                        }
                    },
                )
                // Not zero, which is what the cache holds and not what the next turn will.
                // Folding frees most of the window and never all of it: the summary and
                // the turns kept verbatim are sent again every turn. Reporting zero told
                // everything that sizes itself against the window that the whole of it was
                // free, and the next attachment was measured against a window that was not
                // there.
                folded.copy(contextUsed = folded.estimatedPromptTokens())
            }
        }
    }

    /** Clears the conversation and the model's KV cache, keeping the model loaded. */
    fun newChat() {
        stop()
        viewModelScope.launch {
            // Awaited, not merely cancelled: a generation still unwinding writes into the
            // transcript this is about to replace, and would do so after the engine cache
            // has already been reset underneath it.
            generationJob?.join()
            runtime.resetContext()
            conversationId = null
            _uiState.update {
                it.copy(
                    transcript = emptyList(),
                    compaction = null,
                    contextUsed = 0,
                    error = null,
                )
            }
        }
    }

    /** Folds earlier turns immediately, rather than waiting for the context to fill. */
    fun compactNow() {
        if (_uiState.value.isGenerating || _uiState.value.isCompacting) return
        viewModelScope.launch { compactIfNeeded(force = true) }
    }

    /**
     * Reopens a past conversation.
     *
     * The KV cache holds whichever conversation was last generated, so it is cleared: the
     * engine's prefix matching would otherwise find no common prefix and silently
     * re-decode anyway, but clearing makes the state defined rather than accidental.
     */
    fun openConversation(id: Long) {
        viewModelScope.launch {
            stop()
            generationJob?.join()
            runtime.resetContext()

            // Behind the write queue: a reply from the turn that just ended may not have
            // been inserted yet, and reopening the same chat without it would show a
            // question with no answer and then resend it.
            val conversation = writer.inOrder { conversation(id) }
            if (conversation == null) {
                // Deleted between the tap and this read; adopting the id would make the
                // next message violate the foreign key.
                newChat()
                return@launch
            }

            val messages = writer.inOrder { messages(id) }
            conversationId = id
            nextEntryId = messages.size.toLong()

            // A conversation continued under a different model would mix two models'
            // voices in one transcript, and the history would not say which said what.
            val currentModel = _uiState.value.modelName
            val mismatch = conversation.modelName != null &&
                currentModel != null &&
                conversation.modelName != currentModel

            _uiState.update { state ->
                val reopened = state.copy(
                    transcript = messages.toTranscript(
                        conversation.compactionSummary?.let { conversation.compactionThroughIndex },
                    ),
                    compaction = conversation.compactionSummary?.let {
                        Compaction(it, conversation.compactionThroughIndex, 0)
                    },
                    error = if (mismatch) {
                        "This chat was written by ${conversation.modelName}. Replies will " +
                            "now come from $currentModel."
                    } else {
                        null
                    },
                )
                // Not zero. The cache is empty because the model has not read this
                // conversation yet, but the next turn will read all of it, and everything
                // that sizes itself against what is left took the zero for a free window.
                // Reopen a long chat, attach a document, and it was measured against a
                // window that was already spoken for.
                reopened.copy(contextUsed = reopened.estimatedPromptTokens())
            }
        }
    }

    /** Saves settings for the loaded model. Context length applies at the next load. */
    fun savePreferences(preferences: ModelPreferences) {
        val model = preferencesKey ?: return
        viewModelScope.launch {
            runtime.saveSettings(model, preferences)
            _uiState.update { it.copy(preferences = preferences) }
        }
    }

    fun resetPreferences() {
        val model = preferencesKey ?: return
        viewModelScope.launch {
            runtime.resetSettings(model)
            _uiState.update { it.copy(preferences = ModelPreferences()) }
        }
    }

    /** Deletes a conversation; if it is the open one, the screen returns to a blank chat. */
    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            // Stopped and awaited first when it is the open one, for the reason newChat
            // gives about the same wait: a turn still unwinding writes a reply into this
            // conversation, and if the rows have gone by then the insert has no parent to
            // hang from. The attachments are worse, because those are files, and the
            // prompt reader on the engine's thread may still have one open when this
            // deletes it. Both races were open while the stop happened last.
            val wasOpen = conversationId == id
            if (wasOpen) {
                stop()
                generationJob?.join()
            }

            // Read before deleting: the rows are what says which files were attached, and
            // once they are gone nothing else on disk remembers, so the photos would stay
            // forever in a folder the user cannot see. Behind the write queue, so a reply
            // still being written is deleted with the rest rather than after it.
            val orphaned = writer.inOrder {
                val attached = messages(id).flatMap { it.attachments.decodeAttachments() }
                deleteConversation(id)
                attached
            }
            staging.discard(orphaned)
            if (wasOpen) newChat()
        }
    }

    /** Stops the running generation, keeping whatever has been produced so far. */
    fun stop() {
        runtime.cancel()
        generationJob?.cancel()
    }

    /**
     * Settles the screen on one finished pass, and says what it settled on.
     *
     * [raw] is everything the engine produced, which is ahead of the screen by up to one
     * coalescing window. The string returned is what the entry now shows, and it is what
     * the turn writes to storage once it has finished asking for tools, so what is shown,
     * what is re-read on reopening, and what is sent as history next turn are all the same
     * string.
     */
    private fun applyCompletion(event: GenerationEvent.Completed, raw: String): String {
        val parsed = parseAssistantReply(raw)
        val streamed = _uiState.value.transcript.lastOrNull()
        // Whichever source has it. llama.cpp separates thinking itself for the formats it
        // knows; for the rest it comes out of the buffer, and what was already on screen
        // is the last resort.
        val reasoning = event.reasoning.ifEmpty { null } ?: parsed.reasoning ?: streamed?.reasoning
        // The engine only cleans a reply when llama.cpp's parser recognised a tool call in
        // it. For an ordinary reply it hands the whole thing back untouched, thinking and
        // all, and preferring that is how the same reasoning came to be shown twice: once
        // in the block where it belongs and again as the answer. That is what "the model is
        // quoting the prompt at me" turned out to be. Text the engine did not change is
        // text it did not parse, so the local split is the better of the two.
        val engineCleaned = event.content.isNotEmpty() && event.content != raw
        // Stripped when the engine did not clean it, because then nothing has. A call whose
        // name belongs to no tool cannot be salvaged into anything, so neither parser
        // removes it and the invocation itself was what the user read.
        val answer = if (engineCleaned) event.content else parsed.answer.withoutToolMarkup()

        // The template test at load asks whether being told not to think changes the
        // prompt; it cannot ask whether the weights care. This is the other half of that
        // question, answered by the only thing that can answer it.
        if (_uiState.value.thinkingSwitchWasIgnored(reasoning)) {
            preferencesKey?.let(runtime::rememberIgnoresThinkingSwitch)
            _uiState.update { it.copy(supportsThinking = false) }
        }
        val canonical = canonicalText(reasoning, answer)

        updateLastEntry {
            it.copy(
                text = canonical,
                answer = answer,
                reasoning = reasoning,
                isStreaming = false,
                isReasoningInProgress = false,
                tokensPerSecond = event.stats.decodeTokensPerSecond,
                timeToFirstTokenMs = event.stats.timeToFirstTokenMs,
                generatedTokens = event.stats.generatedTokens,
            )
        }
        recordWork(event.stats)
        _uiState.update {
            it.copy(
                contextUsed = event.stats.contextUsed,
                contextSize = event.stats.contextSize,
                // Falls back to what is already there rather than clearing it. A reply that
                // ends normally has no warning of its own, and setting null wiped whatever
                // the turn had already reported: a write that would not go through was
                // announced and then quietly withdrawn by the answer arriving. Nothing goes
                // stale, because send clears the line at the start of every turn.
                error = event.reason.warning() ?: it.error,
            )
        }
        return canonical
    }

    /**
     * Finishes a reply that was stopped or that failed part way.
     *
     * The buffer runs ahead of the screen, so it is applied in full before anything else:
     * stopping must not silently discard the tokens produced since the last publish. An
     * empty buffer means nothing was produced, and a blank turn is worse than no turn, so
     * the placeholder is removed instead of being written down.
     */
    private fun finishInterrupted(raw: String) {
        if (raw.isEmpty()) {
            _uiState.update { state ->
                if (state.transcript.lastOrNull()?.isStreaming != true) {
                    state
                } else {
                    state.copy(transcript = state.transcript.dropLast(1))
                }
            }
            return
        }

        val parsed = parseAssistantReply(raw)
        // Closed off rather than left as the stream had it: thinking that was cut off
        // mid-tag reopens as an unterminated block and swallows the answer after it.
        val canonical = canonicalText(parsed.reasoning, parsed.answer)
        updateLastEntry {
            it.copy(
                text = canonical,
                answer = parsed.answer,
                reasoning = parsed.reasoning ?: it.reasoning,
                isStreaming = false,
                isReasoningInProgress = false,
            )
        }
        persistReply(canonical, stats = null)
    }

    /**
     * Writes the reply a turn settled on.
     *
     * Once per turn, not once per pass. A turn that uses a tool completes two passes or
     * more, and this used to run at the end of each: the screen showed one answer with the
     * tool folded into it, storage held the interim reply that asked for the tool and then
     * the real one, and reopening the chat produced a conversation the user had never had
     * and then sent it back to the model as history.
     *
     * A stopped reply has no numbers, since they only arrive with a completion, and it is
     * written without them rather than with invented ones.
     */
    private fun persistReply(text: String, stats: GenerationStats?) {
        val id = conversationId ?: return
        val reasoningMs = _uiState.value.transcript.lastOrNull()?.reasoningMs
        viewModelScope.launch {
            reportingFailure { writer.reply(id, text, stats, reasoningMs) }
        }
    }

    private suspend fun reportingFailure(write: suspend () -> Unit) =
        writeReportingFailure(write) { _uiState.update { state -> state.copy(error = it) } }

    /**
     * Folds one pass into the lifetime ledger.
     *
     * Per pass rather than per turn, which is the one part of this that was already right.
     * A turn that searched before answering really did decode twice, and the tab says how
     * much work the phone has done. Kept apart from the reply for that reason: the row
     * carries the answer's own numbers, the ledger carries the totals, and deleting the
     * chat later does not un-count the work.
     */
    private fun recordWork(stats: GenerationStats) {
        val model = _uiState.value.modelName ?: return
        viewModelScope.launch { reportingFailure { writer.work(model, stats) } }
    }

    private fun applyStreamedText(
        raw: String,
        parsed: AssistantReply,
        reasoningEndedAt: Long?,
        startedAt: Long,
    ) = updateLastEntry {
        it.copy(
            text = raw,
            reasoning = parsed.reasoning,
            answer = parsed.answer,
            isReasoningInProgress = parsed.isReasoningInProgress,
            reasoningMs = reasoningEndedAt?.minus(startedAt),
        )
    }

    private fun entry(role: ChatRole, text: String) =
        TranscriptEntry(id = nextEntryId++, role = role, text = text)

    private fun updateLastEntry(transform: (TranscriptEntry) -> TranscriptEntry) {
        _uiState.update { state ->
            val transcript = state.transcript
            if (transcript.isEmpty()) return@update state
            state.copy(transcript = transcript.dropLast(1) + transform(transcript.last()))
        }
    }

    override fun onCleared() {
        runtime.cancel()
    }
}

/**
 * How often streamed text reaches the screen, in milliseconds.
 *
 * Two frames at 60 Hz. Below this the work per update: re-parse, recompose, re-measure,
 * re-scroll: starts to overlap itself and the transcript visibly judders; above it the
 * text arrives in visible chunks.
 */
/**
 * True when a reply came back reasoning after reasoning was switched off.
 *
 * Proof that this model ignores the switch, which is worth one wrong reply to learn and
 * nothing after that: the finding is kept against the file rather than the session.
 */
private fun ChatUiState.thinkingSwitchWasIgnored(reasoning: String?): Boolean =
    supportsThinking && !preferences.thinking && reasoning != null

/**
 * The document, as the model reads it.
 *
 * Named and fenced, so the model can tell the document from the question about it. Saying
 * when it was cut short matters more than it looks: a model that believes it read the whole
 * thing will answer confidently about an ending that was never there.
 */
private fun StagedDocument.asPrompt(): String = buildString {
    append("Document: ").append(name).append('\n')
    append("\"\"\"\n").append(text.trim()).append('\n')
    if (wasTrimmed) append("[cut short: the rest did not fit]\n")
    append("\"\"\"\n\n")
}

/**
 * How many characters of a document may be sent.
 *
 * Half of what is left of the window, not half of the window. The first version used the
 * whole size and a fresh conversation to reason about, and attaching a spreadsheet to a
 * chat that already had one exchange in it filled the context and lost the turn.
 *
 * Two characters to a token, which is pessimistic for prose and about right for the thing
 * people actually attach. English runs nearer four, but a comma separated file of names,
 * dates and numbers tokenises far worse than a paragraph, and it was exactly that file
 * which overran. Being wrong in this direction costs some of a document; being wrong in
 * the other direction costs the whole reply.
 */
private fun documentBudget(state: ChatUiState): Int {
    val size = state.contextSize.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
    val remaining = (size - state.contextUsed).coerceAtLeast(0)
    return remaining * CHARS_PER_TOKEN / DOCUMENT_SHARE
}

/** Two characters to a token: what dense, comma heavy text actually costs. */
private const val CHARS_PER_TOKEN = 2

/** Half the window. The conversation, the instructions and the reply share the rest. */
private const val DOCUMENT_SHARE = 2

/** Used before a model is loaded and its real window is known. */
private const val DEFAULT_CONTEXT_TOKENS = 4096

private const val STREAM_FRAME_MS = 33L

private const val COMPACTION_NOTE = "Earlier turns folded into a summary to make room."

/** Where the open conversation is remembered across a process the system reclaimed. */
private const val LAST_CONVERSATION = "lastConversation"

/**
 * Runs a write, and reports it rather than dying when it fails.
 *
 * Every write in the chat is launched rather than awaited, so the screen never waits on the
 * disk. That also means nothing is watching: an exception in one of those coroutines has no
 * catch above it and no handler on the scope, so a full disk or a database that would not
 * open took the process with it rather than the write.
 *
 * The cause is logged and the reader is told the consequence. There is nothing they can do
 * about an SQLite error, and the thing they can act on while the conversation is still on
 * screen is that it will not be there later.
 */
private suspend fun writeReportingFailure(write: suspend () -> Unit, report: (String) -> Unit) {
    try {
        write()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        Log.w("OpenWeights", "a chat write did not go through", failure)
        report(STORAGE_FAILED)
    }
}

/**
 * What a write that would not go through is worth saying.
 *
 * Names the consequence rather than the cause, because the cause is a database and the
 * consequence is that this conversation will not be here tomorrow, which is the part
 * somebody can act on while it is still on screen.
 */
private const val STORAGE_FAILED =
    "This chat could not be saved. It is still here now, but it will not be when you come back."

/**
 * How long an answer should be.
 *
 * Always sent, with or without tools, because length is the thing that decides what a reply
 * costs here. Asked "Gojo vs Sukuna" this model wrote two thousand nine hundred tokens,
 * seventy-one percent of the window, and took five minutes and thirty-nine seconds over it.
 * None of that was searching: it was one pass with no tools and no calls. It restated the
 * question, numbered seven sections, argued with itself in the open with thinking switched
 * off, and reached "Refine the Argument" without ever having given an answer.
 *
 * A hosted assistant answers that in a paragraph. The difference is not the hardware, it is
 * that nothing here had ever told the model when to stop. On a phone at seven tokens a
 * second, every hundred words it does not write is fourteen seconds the user does not wait.
 *
 * One sentence, because the first attempt at fixing this was six sentences of "do not" and
 * it made things worse: the model started quoting the rules back to itself and deciding
 * which one applied, on the page, with thinking off. Against the same model on a Mac this
 * sentence produced 286 tokens where the uncapped original produced 2,900.
 */
private const val ANSWER_STYLE: String =
    "Answer from what you know, in a few sentences. Reply with the answer itself."

/**
 * What actually gets sent to the model: the compaction summary, if any, followed by the
 * turns that were not folded into it.
 */
internal fun ChatUiState.engineMessages(): List<ChatMessage> {
    val instructions = listOfNotNull(
        // A model cannot tell that "this year's final" is past its training data if nobody
        // tells it what year it is, and it will answer from memory rather than look. Eight
        // tokens, and on the routing set it was most of the difference between eleven right
        // out of twenty four and eighteen.
        "Today is ${LocalDate.now()}.",
        ANSWER_STYLE,
        preferences.systemPrompt.takeIf { it.isNotBlank() },
        toolInstruction(mode, preferences.toolPrompt).takeIf { supportsTools && toolsAvailable },
        // Part of the instructions, not a turn of its own. Sent separately this was a second
        // system message beside them, and Gemma 3's template raises rather than renders when
        // the roles do not alternate: every turn after the first fold came back as an error,
        // and kept coming back, because the next turn rebuilt the same prompt.
        compaction?.let { "Summary of the earlier conversation:\n${it.summary}" },
    ).joinToString("\n\n")

    val system = instructions
        .takeIf { it.isNotBlank() }
        ?.let { listOf(ChatMessage.text(ChatRole.SYSTEM, it)) }
        .orEmpty()

    val remaining = compaction
        ?.let { transcript.drop(it.foldedThroughIndex + 1) }
        ?: transcript

    return (system + remaining.map { it.toChatMessage() }).asExchange()
}

/**
 * The prompt as a strict exchange: one system turn, then user and assistant in turn.
 *
 * Several widely used templates require this rather than prefer it, and refuse to render
 * anything at all otherwise. Three things here can break it, and each was reachable: the
 * fold keeps a fixed number of recent entries and nothing makes that boundary land on a
 * question; a stop before the first token drops the empty reply, so the next question
 * follows the last one directly; and the summary used to be sent as its own system turn.
 *
 * Neighbours of the same role are joined rather than dropped, because both halves are
 * things the user said or the model wrote and neither is ours to discard. An answer left
 * at the front with no question before it is dropped, because there is nothing to join it
 * to and its question has already been folded away.
 */
private fun List<ChatMessage>.asExchange(): List<ChatMessage> {
    val system = takeWhile { it.role == ChatRole.SYSTEM }
    val body = drop(system.size).dropWhile { it.role == ChatRole.ASSISTANT }

    // Instructions with nothing to answer are not a prompt. A fold that keeps only answers
    // leaves this, and sending it asks the model to reply to nobody: refused by the
    // templates that check, and answered at random by the ones that do not. Empty is what
    // the caller already treats as nothing to send.
    if (body.isEmpty()) return emptyList()

    return system + body.fold(mutableListOf()) { kept, message ->
        val previous = kept.lastOrNull()
        if (previous != null && previous.role == message.role) {
            kept[kept.lastIndex] = previous.copy(parts = previous.parts + message.parts)
        } else {
            kept += message
        }
        kept
    }
}

/**
 * The state with the outcome of an attachment folded into it.
 *
 * One place where the three answers land, so a refusal cannot be reported by one path and
 * swallowed by another. Which of the two staging slots a file fills is a property of the
 * file rather than of the caller.
 */
private fun ChatUiState.after(staged: Staged): ChatUiState = when (staged) {
    is Staged.Files -> copy(staged = this.staged + staged.files)
    is Staged.Document -> copy(stagedDocument = staged.document)
    is Staged.Refused -> copy(error = staged.why)
}

/**
 * Why a message cannot be sent right now, or null when it is not worth saying.
 *
 * Generating is not worth saying: the composer shows Stop rather than Send, so reaching it
 * at all means a tap beat the recomposition. The other two are the cold start, where
 * somebody types before the weights are mapped, and those are worth saying because the
 * alternative is a question that simply disappears.
 */
private fun ChatUiState.refusalReason(): String? = when {
    isLoadingModel -> "The model is still loading. Ask again in a moment."
    modelName == null -> "No model is loaded yet. Choose one in Models."
    else -> null
}

/**
 * The state with a reply that came back empty taken out of it, or null if there was none.
 *
 * A turn can complete and leave nothing behind. The pass finished, so the entry was settled
 * rather than dropped, and what stayed on screen was an empty bubble with a metrics row
 * under it: nothing said what had happened, and the empty reply was written to storage as
 * well, so it came back on reopening and was resent as an empty assistant turn.
 *
 * Only ever reached when the model itself came back empty, which a small one does when the
 * template renders something it will not continue. Stop takes the cancellation path and
 * never arrives here.
 */
private fun ChatUiState.droppingEmptyReply(): ChatUiState? {
    val last = transcript.lastOrNull() ?: return null
    val nothingInIt = last.role == ChatRole.ASSISTANT &&
        last.text.isBlank() &&
        last.blocks.isEmpty()
    if (!nothingInIt) return null

    return copy(
        transcript = transcript.dropLast(1),
        error = error ?: "The model returned an empty reply. Ask again, or put it another way.",
    )
}

/**
 * Stored messages as the screen shows them.
 *
 * Its own function because reopening has to rebuild everything the live transcript carries,
 * and one part of that was quietly missing: the marker saying earlier turns were folded
 * away is written when the fold happens and was not restored, so a compacted conversation
 * came back looking as though nothing had been summarised and the gap in it was unexplained.
 *
 * @param foldedThrough index of the last entry a fold covered, or null if there was none.
 */
private fun List<MessageEntity>.toTranscript(foldedThrough: Int?): List<TranscriptEntry> =
    mapIndexed { index, message ->
        val parsed = parseAssistantReply(message.text)
        TranscriptEntry(
            id = index.toLong(),
            role = ChatRole.entries.firstOrNull { it.wireName == message.role }
                ?: ChatRole.ASSISTANT,
            text = message.text,
            reasoning = parsed.reasoning,
            answer = parsed.answer,
            tokensPerSecond = message.tokensPerSecond,
            timeToFirstTokenMs = message.timeToFirstTokenMs,
            generatedTokens = message.generatedTokens,
            reasoningMs = message.reasoningMs,
            attachments = message.attachments.decodeAttachments(),
            compactionNote = COMPACTION_NOTE.takeIf {
                foldedThrough != null &&
                    index == foldedThrough + 1
            },
        )
    }

/**
 * A pessimistic guess at what the next prompt will occupy.
 *
 * Wanted only just after a fold, where the engine's own reading says nothing useful: the
 * cache was reset, so it reports empty, while the next turn will re-decode the summary and
 * whatever was kept verbatim. Two characters to a token, the same deliberately pessimistic
 * ratio the attachment budget uses and for the same reason: over-estimating what is spent
 * costs some of a document, and under-estimating it costs the whole reply.
 */
internal fun ChatUiState.estimatedPromptTokens(): Int =
    engineMessages().sumOf { it.text.length } / CHARS_PER_TOKEN

/**
 * A transcript entry as the engine sees it.
 *
 * Attachments come first: a question about a picture reads better after the picture, and
 * models are trained on that order.
 */
private fun TranscriptEntry.toChatMessage(): ChatMessage = ChatMessage(
    role = role,
    parts = attachments + MessagePart.Text(text),
)

/**
 * The one string that stands for a reply everywhere.
 *
 * It is what the entry holds, what is written to storage, and what is resent as history,
 * so it has to read back through [parseAssistantReply] as the same reply the user saw.
 *
 * Rebuilt from the split parts rather than kept as the raw stream, because for the formats
 * llama.cpp recognises the raw stream still holds tool invocation syntax that was lifted
 * out of what is displayed. Storing it would make a chat change what it says the moment it
 * is reopened. Where nothing was lifted out this returns the stream unchanged.
 */
private fun canonicalText(reasoning: String?, answer: String): String =
    if (reasoning.isNullOrEmpty()) answer else "<think>$reasoning</think>$answer"

/**
 * Warns when a chat carried over to a new model contains media that model cannot read.
 *
 * The transcript is resent as history on the next reply, and the engine drops attachments
 * a model has no projector for, so those turns arrive as text with the picture missing.
 */
private fun List<TranscriptEntry>.unreadableWarning(support: MediaSupport): String? {
    val dropped = flatMap { it.attachments }.filterNot { support.accepts(it.kind) }
    if (dropped.isEmpty()) return null
    return "This chat has ${dropped.size} attachment(s) the new model cannot read. Replies " +
        "will be based on the text alone."
}

/**
 * What to tell the model about its tools.
 *
 * Comes from settings, not from here. An app that quietly prepends instructions to every
 * conversation is an app whose behaviour its user cannot account for, so the text is a
 * preference they can read, edit, or empty.
 *
 * Plan mode is the one exception, and it is one the user selected by typing `/plan`: the
 * instruction is the mode.
 */
private fun toolInstruction(mode: AgentMode, configured: String): String? = when (mode) {
    AgentMode.PLAN ->
        "You have tools available. Do not call them. Say which you would use and why."

    AgentMode.ASK, AgentMode.AUTO -> configured.takeIf { it.isNotBlank() }
}

/** A short human label for an attachment, used where there is no text to go on. */
internal fun MessagePart.File.describe(): String = name ?: when (kind) {
    MediaKind.IMAGE -> "Image"
    MediaKind.AUDIO -> "Audio"
    MediaKind.VIDEO -> "Video"
    MediaKind.OTHER -> "File"
}

/** Turns engine failures into something a person can act on. */
private fun Throwable.userMessage(): String = when {
    // What llama.cpp says when the KV cache has no slot left for the batch. The words it
    // uses name an internal return code, and the thing to do about it is not "decode"
    // anything, so it is translated rather than passed through.
    message?.contains(NO_KV_SLOT) == true ->
        "This turn outgrew the context window, usually because a page it read was long. " +
            "The conversation is still here. Ask again, or raise the context length in " +
            "model settings."

    else -> message ?: "Generation failed (${this::class.simpleName})."
}

private const val NO_KV_SLOT = "failed to decode prompt"

/** Some stop reasons need surfacing; a normal end of turn is not. */
private fun StopReason.warning(): String? = when (this) {
    StopReason.CONTEXT_FULL ->
        "The context window is full. Start a new chat, or raise the context length in " +
            "model settings."

    StopReason.END_OF_TURN, StopReason.MAX_TOKENS, StopReason.CANCELLED, StopReason.ERROR -> null
}

/**
 * How often the device's thermal level is re-read while a reply is being written.
 *
 * Often enough to catch a phone heating up mid-answer, rarely enough that the reading
 * itself is not part of the load it is measuring.
 */
private const val THERMAL_SAMPLE_MS = 4_000L

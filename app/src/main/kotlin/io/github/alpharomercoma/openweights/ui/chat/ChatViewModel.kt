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
import io.github.alpharomercoma.openweights.core.common.model.assistantHistoryText
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
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.ToolNotes
import io.github.alpharomercoma.openweights.model.StagedDocument
import io.github.alpharomercoma.openweights.ui.ReplyNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    /**
     * The reply exactly as it was decoded, kept only for as long as the process lives.
     *
     * [text] is what is shown and stored, and it is not the same string: see
     * [assistantHistoryText]. This is what goes back to the engine as history, because it
     * is the only version that matches the tokens still sitting in the KV cache. Null on a
     * turn that was reloaded from storage, where the cache is empty anyway and there is
     * nothing to match.
     */
    val history: String? = null,
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
    /**
     * What the tools returned earlier in this chat, kept because the transcript does not.
     *
     * Held here rather than in the turn loop because it outlives a turn, and rather than in
     * storage because it is not part of the conversation: nothing shows it, nothing edits it,
     * and a chat reopened from disk starts it again empty. See [ToolNotes].
     */
    val toolNotes: ToolNotes = ToolNotes(),
    val contextUsed: Int = 0,
    val contextSize: Int = 0,
    /**
     * How this model tokenises this conversation, as the last completed pass reported it.
     *
     * Null until something has been generated, which is the only time anything has to be
     * guessed. See [estimatedPromptTokens].
     */
    val charsPerToken: Float? = null,
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
     * Every buffer holding the weights, largest first, in MiB.
     *
     * Shown beside the processor setting, because that setting is a request and this is
     * what came of it. llama.cpp reports the layer count it was asked for whether or not a
     * backend attached, so the only honest answer to "did that do anything" is which
     * buffers the tensors ended up in.
     */
    val offloadBuffers: List<Pair<String, Int>> = emptyList(),
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
            // Only when it is not the default, which is the same rule the rest of this line
            // follows. A mode was choosable by typing and then invisible: nothing anywhere
            // said the app was in plan mode, so the only evidence was tools not running.
            mode.takeIf { it != ChatUiState().mode }?.label,
        ).joinToString(" · ")

    val canSend: Boolean get() = modelName != null && !isGenerating && !isLoadingModel
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val runtime: ModelRuntime,
    private val compactor: ConversationCompactor,
    private val staging: Staging,
    private val writer: ChatWriter,
    private val turns: TurnRunner,
    private val notifier: ReplyNotifier,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    /** Completed by the approval buttons, so the agent can wait on a human. */
    private var approval: CompletableDeferred<Boolean>? = null
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * What is attached to the message being typed.
     *
     * Built here rather than injected, because it needs this view model's scope and its state,
     * and a Hilt binding for something that takes both would be a longer way to say the same.
     */
    private val attachments = Attaching(staging, viewModelScope, _uiState)

    /** Folding older turns into a summary. Built here for the reason [attachments] is. */
    private val folding = Folding(compactor, writer, _uiState)

    private var generationJob: Job? = null
    private var thermalJob: Job? = null

    /**
     * Searching the history, collected beside this state rather than inside it.
     *
     * Built here rather than injected because it needs this view model's scope: a search in
     * flight when the screen goes should go with it.
     */
    val search = ChatSearch(writer, viewModelScope)
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

    /** The weights currently loaded, kept so a turn can tell whether they are still there. */
    private var loadedFile: File? = null

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
            writer.conversations()
                // Collected for the life of the view model and awaited by nobody, so an
                // SQLite error here had no catch above it and took the process with it,
                // from `init`, before there was a screen to say anything on.
                .catch { failure ->
                    Log.w("OpenWeights", "the conversation list could not be read", failure)
                    _uiState.update { it.copy(error = CHATS_UNREADABLE) }
                }
                .collect { rows ->
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
     *
     * @param replacing the model being loaded, whose name the top bar keeps while the
     * weights are remapped. Everything that could be wrong about it is still cleared: the
     * processor, the window, and what it can read are all properties of a load that has not
     * happened yet. Only the name survives, and only because moving the processor took the
     * title down to "Choose a model" for the seconds it took, which reads as having lost
     * the model rather than as having moved it. Nothing can act on the name meanwhile, since
     * the composer is closed for the whole of a load.
     */
    private suspend fun forgetLoadedModel(replacing: File? = null) {
        // Cleared here as well as on the way in. A load that fails leaves nothing loaded, and
        // a stale file here would have the next turn check weights that are not in use.
        loadedFile = null
        val abandoned = _uiState.value.staged
        _uiState.update {
            it.copy(
                staged = emptyList(),
                modelName = replacing?.nameWithoutExtension,
                modelQuantization = null,
                backend = null,
                contextSize = 0,
                contextUsed = 0,
                mediaSupport = MediaSupport(),
                supportsThinking = false,
                supportsTools = false,
                supportsReasoningEffort = false,
            )
        }
        // The screen is left in one step above, so nothing observes a half-forgotten model.
        // What is left is the copies on disk, which is the part that has to suspend.
        attachments.discard(abandoned)
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
        contextLength?.let { return ModelLoadParams(contextLength = it, gpuLayers = layers) }
        // Only computed when it will be used. Reading the header is cheap but it is still a
        // file read on the path a cold start always takes, and a user who has chosen a window
        // has already answered the question this asks.
        val automatic = if (preferences.contextLength == ModelPreferences.AUTOMATIC) {
            runtime.windows.defaultFor(modelFile, runtime.projectorFor(modelFile))
        } else {
            ModelLoadParams.DEFAULT_CONTEXT_LENGTH
        }
        return preferences.toLoadParams(layers, automatic)
    }

    private suspend fun performLoad(
        modelFile: File,
        contextLength: Int?,
        keepConversation: Boolean,
    ) {
        _uiState.update { it.copy(isLoadingModel = true, error = null) }

        // Inside the catch, not before it. Settings come from DataStore and the projector
        // from a directory listing, and a phone whose storage has gone wrong fails those
        // exactly as readily as it fails the weights. Outside, they were two unwatched reads
        // in the one path a cold start always takes. The settings come back out because the
        // screen shows them, and they are only known once the read has succeeded.
        runCatching {
            val settings = runtime.settingsFor(modelFile.name)
            val projector = runtime.projectorFor(modelFile)
            forgetLoadedModel(replacing = modelFile)
            runtime.load(modelFile, loadParamsFor(modelFile, settings, contextLength), projector)
            settings
        }.onSuccess { preferences ->
            // The cache belonged to the old weights. Clearing it makes the next reply
            // re-read the transcript, which is what carries the conversation across.
            runtime.resetContext()
            runtime.rememberChoice(modelFile)
            if (!keepConversation) conversationId = null
            preferencesKey = modelFile.name
            loadedFile = modelFile
            val info = runtime.loadedModel
            val support = info?.mediaSupport ?: MediaSupport()
            _uiState.update {
                it.copy(
                    isLoadingModel = false,
                    backend = runtime.backendName(),
                    offloadBuffers = info?.offloadBuffers.orEmpty(),
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
                    toolNotes = if (keepConversation) it.toolNotes else ToolNotes(),
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
            // The name was kept across the swap for the top bar. A load that failed holds no
            // weights, so it goes here rather than staying to describe nothing.
            _uiState.update {
                it.copy(isLoadingModel = false, modelName = null, error = failure.userMessage())
            }
        }
    }

    /**
     * Whether the weights behind the loaded model have been deleted, and letting go if so.
     *
     * Checked at the start of a turn, which is the only moment it matters and is one stat of
     * a file the process already has open. A model can go while it is loaded: deleted from
     * the Models tab, removed by a file manager, on a card that was taken out. The engine has
     * it mapped and carries on regardless, so the screen keeps naming a model that is not
     * there, Send stays live, and what the turn eventually makes of an unlinked mapping
     * surfaces minutes later as a generic failure.
     *
     * Told at the point of asking rather than at the point of deleting, because deleting is
     * not the only way it happens and a check at the boundary catches all of them.
     */
    private fun loadedModelHasGone(): Boolean {
        val file = loadedFile ?: return false
        if (file.exists()) return false
        loadedFile = null
        viewModelScope.launch {
            forgetLoadedModel()
            reportError("$MODEL_GONE ${file.nameWithoutExtension} is no longer on this device.")
        }
        return true
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
        if (loadedModelHasGone()) return false
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
    fun attach(uri: Uri) = attachments.attach(uri)

    /** Removes a staged attachment and deletes the copy that was made of it. */
    fun removeStaged(attachment: MessagePart.File) = attachments.remove(attachment)

    /** Stages a text document for the next question, or clears the staged one. */
    fun stageDocument(uri: Uri?) = attachments.stageDocument(uri)

    /**
     * Discards the last model reply and asks again.
     *
     * The engine reuses the cached prompt prefix, so a regeneration costs only new tokens.
     */
    fun regenerate() {
        val state = _uiState.value
        if (state.isGenerating || state.transcript.none { it.role == ChatRole.ASSISTANT }) return
        // The same check send makes, for the same reason: this runs the model too.
        if (loadedModelHasGone()) return

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
            //
            // Guarded, because the busy flag was claimed above and a read that throws here
            // used to leave it claimed forever: not a crash, a chat showing Stop with nothing
            // behind it, unusable until the app was killed.
            val discarded = runCatching {
                writer.inOrder {
                    conversationId?.let { id ->
                        val stored = messages(id)
                        // The same rule the transcript used: the trailing run of replies,
                        // which starts after the last thing that was not one.
                        val firstDiscarded =
                            stored.indexOfLast { it.role != ChatRole.ASSISTANT.wireName } + 1
                        stored.getOrNull(firstDiscarded)?.let { deleteFrom(id, it.id) }
                    }
                }
            }
            if (discarded.isFailure) {
                // Refused rather than generated anyway. The reply on screen is already gone
                // and the stored one is not, so answering would leave two of them in the
                // conversation and the user reading a history they never had.
                Log.w("OpenWeights", "a reply could not be discarded", discarded.exceptionOrNull())
                _uiState.update { it.copy(isGenerating = false, error = STORAGE_FAILED) }
                return@launch
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

                override fun onSteps(steps: List<AgentStep>) {
                    updateLastEntry { it.copy(blocks = it.blocks + steps.map(TurnBlock::Step)) }
                    // Kept for the turns after this one. Within this turn the results are
                    // already in the loop's own messages, which is why nothing here is sent
                    // twice: the prompt is built before the first tool runs.
                    _uiState.update {
                        it.copy(toolNotes = it.toolNotes.withSteps(steps, turns::toolNamed))
                    }
                }

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
                    // Not gated on the template being able to render tools any more.
                    // TurnRunner puts the definitions in the conversation for a model whose
                    // template drops them, which is two of the three families tested here,
                    // and deciding here that they cannot have tools is what made that
                    // silent.
                    withTools = state.toolsAvailable,
                    // The same notes the prompt was built with, read from the same snapshot.
                    // Taking them from the live state instead would seed the guard from
                    // whatever this turn had already found, which is not what is in the
                    // question the model is about to answer.
                    notes = state.toolNotes,
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
        // Only in plan mode, and only from a reply that actually listed steps. A model that
        // answered instead of planning has proposed nothing, and putting a plan on screen it
        // never wrote would be the app inventing one.
        if (_uiState.value.mode == AgentMode.PLAN) {
            val proposed = _uiState.value.transcript.lastOrNull()?.answer.orEmpty()
            turns.planning.propose(proposed)
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
    /**
     * The plan the app is holding, which the screen shows and the user can tick.
     *
     * Handed out rather than mirrored into [ChatUiState]: it is already a flow, and a second
     * copy would be a second thing to keep in step for nothing.
     */
    val planning: PlanBoard get() = turns.planning

    /** The question the model is waiting on. See [planning]. */
    val asking: AskBoard get() = turns.asking

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
     * The whole of it is in [Folding], which is where the reasoning lives too. What is here
     * is the two things that object cannot see for itself: which conversation this is, read
     * again after the fold in case the user moved, and whether the engine is mid-decode.
     */
    private suspend fun compactIfNeeded(force: Boolean = false) =
        folding.fold(force = force, engineIsDecoding = isDecoding) { conversationId }

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
            turns.planning.clear()
            _uiState.update {
                it.copy(
                    transcript = emptyList(),
                    // Emptied with the transcript it belongs to. A record of what a tool
                    // returned in one chat is not evidence in the next one.
                    toolNotes = ToolNotes(),
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
        // Folding runs the model to write the summary, so it needs the same check send makes.
        if (loadedModelHasGone()) return
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
            // A read that throws used to leave the launch with nothing above it to catch. The
            // chat on screen is untouched by a failed reopen, so the sentence says the one
            // thing that changed: the other chat did not open.
            readReportingFailure(CHAT_UNREADABLE, ::reportError) { reopen(id) }
        }
    }

    private suspend fun reopen(id: Long) {
        // Read before anything is disturbed. The row's existence does not depend on a reply
        // still being written, so this one read needs no queue, and taking it first means a
        // database that will not answer costs the user nothing: the turn they had running is
        // still running, and the sentence they get is about the chat that did not open.
        val conversation = writer.inOrder { conversation(id) }

        stop()
        generationJob?.join()
        runtime.resetContext()

        if (conversation == null) {
            // Deleted between the tap and this read; adopting the id would make the
            // next message violate the foreign key.
            newChat()
            return
        }

        // Behind the write queue, and after the stop: a reply from the turn that just ended
        // may not have been inserted yet, and reopening the same chat without it would show a
        // question with no answer and then resend it.
        val messages = writer.inOrder { messages(id) }
        conversationId = id
        nextEntryId = messages.size.toLong()
        // The board is one object for the whole app, so a plan left in it is on screen in
        // whatever chat is opened next and is pinned to the tail of that chat's prompt.
        // newChat has always cleared it; this is the switch people actually use.
        turns.planning.clear()

        // A conversation continued under a different model would mix two models'
        // voices in one transcript, and the history would not say which said what.
        val currentModel = _uiState.value.modelName
        val mismatch = conversation.modelName != null &&
            currentModel != null &&
            conversation.modelName != currentModel

        _uiState.update { state ->
            val reopened = state.copy(
                // Nothing restores this: the notes were never written down, so a chat opened
                // from storage starts with none and behaves as it did before they existed.
                toolNotes = ToolNotes(),
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

    /**
     * Saves settings for the loaded model.
     *
     * Most of them are read when the next reply starts and so take effect immediately. Two
     * are not: the context window and which processor holds the layers are both fixed when
     * llama.cpp maps the weights, and the only way to change either is to map them again.
     *
     * The processor is reloaded here rather than left for whenever the model next happens to
     * load. Left, it was indistinguishable from a broken switch: the user moved it to GPU,
     * the top bar went on saying CPU because the weights really were still on the CPU, and
     * nothing on screen said that the setting was waiting for an event the user had no
     * reason to expect. The reload costs three to seven seconds on the hardware measured,
     * and the screen already has a state for that.
     *
     * The context window is deliberately not reloaded with it. Growing it can fail on a
     * phone that cannot spare the memory, and failing a load the user did not ask for would
     * leave them with no model at all in exchange for a number they were only editing.
     */
    fun savePreferences(preferences: ModelPreferences) {
        val model = preferencesKey ?: return
        viewModelScope.launch {
            val movedProcessor = _uiState.value.preferences.offload != preferences.offload
            runtime.saveSettings(model, preferences)
            _uiState.update { it.copy(preferences = preferences) }
            // The conversation is kept: only the weights move, and the transcript is text.
            // The cache does not survive, which is why the next reply re-reads it.
            if (movedProcessor) loadedFile?.let { loadModel(it, keepConversation = true) }
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
            reportingFailure {
                val orphaned = writer.inOrder {
                    val attached = messages(id).flatMap { it.attachments.decodeAttachments() }
                    deleteConversation(id)
                    attached
                }
                staging.discard(orphaned)
                if (wasOpen) newChat()
            }
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
                // Blank only when the pass produced nothing, and then there is nothing in
                // the cache to match either: null keeps [text] as the fallback.
                history = raw.takeIf { it.isNotBlank() }?.let {
                    assistantHistoryText(it, event.stats.thinkingPrefilled)
                },
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
                // Read off the pass that just finished: the cache now holds this whole
                // conversation, and its length in characters is right here, so the ratio
                // between them is measured rather than assumed. Kept when a pass reports
                // nothing usable, so one odd reading cannot throw the estimate away.
                charsPerToken = event.stats
                    .charsPerToken(it.engineMessages().sumOf { message -> message.text.length })
                    ?: it.charsPerToken,
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
    private suspend fun finishInterrupted(raw: String) {
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
        // Stripped, as the completed path has always stripped it. Stopping over a call the
        // model was partway through writing left the markup here, and this is the path that
        // writes to storage: the half-written call became the assistant's turn, and every
        // later turn of that conversation was handed an assistant asking for a tool with no
        // result after it. Nothing was ever going to run it, so it is not part of the reply.
        val answer = parsed.answer.withoutToolMarkup()
        // What is left of a reply that was only ever a call is nothing, and a blank turn is
        // worse than no turn, so it goes the same way an empty buffer does.
        if (answer.isBlank() && parsed.reasoning.isNullOrBlank()) {
            _uiState.update { state ->
                if (state.transcript.lastOrNull()?.isStreaming != true) {
                    state
                } else {
                    state.copy(transcript = state.transcript.dropLast(1))
                }
            }
            return
        }
        // Closed off rather than left as the stream had it: thinking that was cut off
        // mid-tag reopens as an unterminated block and swallows the answer after it.
        val canonical = canonicalText(parsed.reasoning, answer)
        updateLastEntry {
            it.copy(
                text = canonical,
                answer = answer,
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
    /**
     * Writes the reply down, and does not come back until it is written.
     *
     * Awaited rather than launched, and uncancellable while it runs. This used to be
     * `viewModelScope.launch { }`: the turn reported itself finished and `isGenerating`
     * cleared while the row was still on its way to the database, so a process kill in that
     * window left a conversation holding a question with no answer. The question was already
     * durable, because the user's own message is written before the model is asked, so the
     * two halves of a turn had different guarantees and the missing half was always the
     * expensive one.
     *
     * [NonCancellable] because the window is not hypothetical on the path that matters most.
     * Stop cancels the job this is called from, and a reply the user watched being written
     * is exactly what they expect to still be there.
     */
    private suspend fun persistReply(text: String, stats: GenerationStats?) {
        val id = conversationId ?: return
        val reasoningMs = _uiState.value.transcript.lastOrNull()?.reasoningMs
        withContext(NonCancellable) {
            reportingFailure { writer.reply(id, text, stats, reasoningMs) }
        }
    }

    private suspend fun reportingFailure(write: suspend () -> Unit) =
        writeReportingFailure(write) { reportError(it) }

    private fun reportError(why: String) = _uiState.update { it.copy(error = why) }

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
 * Two characters to a token, for the one case that has nothing measured to use instead.
 *
 * Named apart from the document budget's own ratio in [Attaching], which happens to share
 * the value, because they answer opposite questions. That one asks how much of a document
 * fits, where being low means attaching less; this one asks how full the window is, where
 * being low means folding early. Sharing a constant made a change for one silently a change
 * for the other.
 */
private const val PESSIMISTIC_CHARS_PER_TOKEN = 2

private const val STREAM_FRAME_MS = 33L

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
 * Runs a read, and says so rather than dying when it fails.
 *
 * The mirror of [writeReportingFailure], and it was missing, which made the rule half a rule:
 * the same database that will not open fails reads and writes alike, and only one of them was
 * being caught. The worst of them ran in the view model's own `init`, so the process died
 * before there was a screen to put a message on, and with no crash reporter that is a launch
 * loop nobody can tell us about.
 *
 * What was lost differs per read, so the caller says it. A drawer that cannot be filled and a
 * chat that will not reopen are different sentences, and neither of them is "the app closed".
 */
private suspend fun readReportingFailure(
    why: String,
    report: (String) -> Unit,
    read: suspend () -> Unit,
) {
    try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        Log.w("OpenWeights", "a chat read did not come back", failure)
        report(why)
    }
}

/** Said when the drawer cannot be filled, which does not stop the chat on screen working. */
private const val CHATS_UNREADABLE =
    "Your saved chats could not be read. This one still works."

/** Said when one chat will not reopen. The one on screen is untouched, so it stays. */
private const val CHAT_UNREADABLE = "That chat could not be opened."

/** Said when the weights have been deleted out from under the engine holding them. */
private const val MODEL_GONE = "Choose a model in Models:"

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
        // Plan mode says its piece whether or not any tool is switched on, because it is a
        // mode the user chose and it changes how the model is meant to answer. Gated on
        // tools being available, "/plan" with everything switched off sent no instruction
        // at all and the mode was a silent no-op.
        toolInstruction(mode, preferences.toolPrompt, anyTools = toolsAvailable),
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

    return (system + remaining.map { it.toChatMessage() }).asExchange().withToolNotes(toolNotes)
}

/**
 * The notes put in front of the question, inside the message that carries it.
 *
 * Inside a user turn rather than beside one, because the prompt is a strict alternation and a
 * turn of its own would be a second user message in a row: the templates that enforce this
 * refuse to render it, which is the same wall the compaction summary hit before it moved into
 * the instructions.
 *
 * And in the last message rather than the first, which is the part that matters on a phone. The
 * instructions are the root of the KV cache and every token of the conversation sits behind
 * them, so a record that grows with each tool call would invalidate the whole prefix every turn
 * and re-prefill a conversation that had not changed. The final user turn is new anyway.
 *
 * Before the question and not after it. Whatever is nearest the end is what a small model
 * answers, and a page of notes in that position gets summarised back instead of used.
 */
private fun List<ChatMessage>.withToolNotes(notes: ToolNotes): List<ChatMessage> {
    val rendered = notes.render() ?: return this
    val last = lastOrNull()?.takeIf { it.role == ChatRole.USER } ?: return this
    // Rebuilt from the whole message rather than by editing its last part: asExchange joins
    // neighbours of one role, so a user turn can arrive here carrying several pieces of text,
    // and prepending to the last of them would file the notes in the middle of the question.
    // Joined only when there is a question to join to. A message that is nothing but an
    // attachment has no text, and the blank line then trailed off the end of the prompt.
    val question = last.text
    val joined = if (question.isBlank()) rendered else "$rendered\n\n$question"
    return dropLast(1) + last.copy(parts = last.files + MessagePart.Text(joined))
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
 * What the next prompt will occupy, from what the last one actually did.
 *
 * Wanted only just after a fold and on reopen, where the engine's own reading says nothing
 * useful: the cache was reset, so it reports empty, while the next turn will re-decode the
 * summary and whatever was kept verbatim.
 *
 * It used to be two characters to a token, borrowed from the attachment budget, where that
 * number is right for a reason that does not apply here: an attachment is dense comma-heavy
 * text and being wrong costs part of a document. English prose runs nearer four, so the
 * borrowed ratio read a conversation as twice as full as it was and folded it again sooner
 * than it needed to, each fold costing a summary generation and a cache reset.
 *
 * So it is measured instead of guessed. Every completed pass reports how many tokens the
 * whole conversation occupies, and the conversation's length in characters is known, so the
 * ratio is a fact about this model and this text rather than about English in general. It
 * still errs the safe way: the count includes the template's own tokens while the characters
 * do not, so the ratio comes out low and the estimate comes out high.
 *
 * [PESSIMISTIC_CHARS_PER_TOKEN] is only for the first fold of a conversation reopened before
 * anything has been generated, where there is nothing to measure yet.
 */
internal fun ChatUiState.estimatedPromptTokens(): Int {
    val chars = engineMessages().sumOf { it.text.length }
    val ratio = charsPerToken ?: return chars / PESSIMISTIC_CHARS_PER_TOKEN
    return (chars / ratio).toInt()
}

/** Characters to a token as this model was last seen to tokenise, or null before then. */
internal fun GenerationStats.charsPerToken(chars: Int): Float? =
    (chars.toFloat() / contextUsed).takeIf { contextUsed > 0 && it.isFinite() && it > 0f }

/**
 * A transcript entry as the engine sees it.
 *
 * Attachments come first: a question about a picture reads better after the picture, and
 * models are trained on that order.
 */
private fun TranscriptEntry.toChatMessage(): ChatMessage = ChatMessage(
    role = role,
    // [history] where there is one, because [text] has been through the parser and the
    // engine's cache holds what came out of the model. See assistantHistoryText.
    parts = attachments + MessagePart.Text(history ?: text),
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
private fun toolInstruction(mode: AgentMode, configured: String, anyTools: Boolean): String? =
    when (mode) {
        // Two wordings, because the first one is a lie when nothing is switched on, and a
        // model told it has tools it does not have writes a plan around using them.
        AgentMode.PLAN -> if (anyTools) {
            "You have tools available. Do not call them. Say which you would use and why."
        } else {
            "Do not act on anything yet. Say what you would do and why, as short steps."
        }

        // Yolo changes what the app does with a call, not what the model is told about
        // tools, so it reads the same instruction the other two running modes do.
        AgentMode.ASK, AgentMode.AUTO, AgentMode.YOLO ->
            configured.takeIf { it.isNotBlank() && anyTools }
    }

/** A short human label for an attachment, used where there is no text to go on. */
internal fun MessagePart.File.describe(): String = name ?: when (kind) {
    MediaKind.IMAGE -> "Image"
    MediaKind.AUDIO -> "Audio"
    MediaKind.VIDEO -> "Video"
    MediaKind.OTHER -> "File"
}

/** Turns engine failures into something a person can act on. */
internal fun Throwable.userMessage(): String = when {
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

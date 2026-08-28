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

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.getSystemService
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.context.Compaction
import io.github.alpharomercoma.openweights.core.common.context.Goal
import io.github.alpharomercoma.openweights.core.common.context.GoalState
import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import io.github.alpharomercoma.openweights.core.common.context.TaskStep
import io.github.alpharomercoma.openweights.core.common.model.AnswerLength
import io.github.alpharomercoma.openweights.core.common.model.AssistantReply
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.GgufFileName
import io.github.alpharomercoma.openweights.core.common.model.MediaKind
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.OutputModality
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
import io.github.alpharomercoma.openweights.core.engine.LoadedModelInfo
import io.github.alpharomercoma.openweights.core.engine.MediaSupport
import io.github.alpharomercoma.openweights.core.engine.StopReason
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.GoalBoard
import io.github.alpharomercoma.openweights.core.tools.Memory
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.ToolNotes
import io.github.alpharomercoma.openweights.core.tools.correlatedWebResearchSources
import io.github.alpharomercoma.openweights.model.StagedDocument
import io.github.alpharomercoma.openweights.runtime.GenerationService
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
    /**
     * This turn's prompt, cached and freshly-decoded tokens together. Null on a turn with no
     * measurement at all (a reply reloaded from storage, or one that never finished), which
     * is a different claim from zero — a turn really can prefill nothing on a full cache hit.
     */
    val promptTokens: Int? = null,
    /** How much of [promptTokens] the KV cache answered for free. See [GenerationStats.cachedTokens]. */
    val cachedTokens: Int? = null,
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
) {
    /**
     * An assistant turn that was opened and never written to.
     *
     * The placeholder goes on screen the moment generation starts, so that a reply appears
     * to begin immediately. If the turn then stops before a single token, that placeholder
     * is all there is: an empty bubble under the question, which reads as an answer of
     * silence rather than as a turn that never ran. Whoever ends the turn early drops it.
     */
    val saidNothing: Boolean
        get() = role == ChatRole.ASSISTANT && isStreaming && text.isBlank() && blocks.isEmpty()
}

/**
 * What one turn was opened on: the history to send, and the state it was built from.
 *
 * Both, and the same both, because the sampler settings, the tool switch and the tool notes
 * have to be the ones the prompt was assembled with. Reading them from the live state at the
 * moment the engine is called instead would take whatever this turn has already changed,
 * which is not the question the model is about to answer.
 */
private data class OpenedTurn(val conversation: List<ChatMessage>, val state: ChatUiState)

/**
 * One rewrite of a question already asked, as a value rather than as a sequence of steps.
 *
 * Held together because the three things done with it have to agree: what the screen shows
 * while the write is in flight, what storage ends up holding, and what the screen goes back
 * to if the write fails. Two of those are the same decision read twice, and the version that
 * computed them separately is the one that let the transcript and the database disagree.
 */
private data class Edit(
    /** Where in the transcript the rewritten question sits. */
    val at: Int,
    val text: String,
    /** Kept with the new wording: editing a question does not detach what came with it. */
    val attachments: List<MessagePart.File>,
    /** Files belonging to turns this drops, whose copies nothing will reference again. */
    val abandoned: List<MessagePart.File>,
    /** Everything before the edited turn, which is all that survives. */
    val kept: List<TranscriptEntry>,
    val invalidatesCompaction: Boolean,
    /** The state as it was, so a failed write can put it back exactly. */
    val before: ChatUiState,
) {
    fun applied(current: ChatUiState): ChatUiState = current.copy(
        transcript = kept,
        error = null,
        // Claimed here, before any suspending work, so a second tap on a second bubble
        // cannot start a rewrite while this one is still being written.
        isGenerating = true,
        compaction = if (invalidatesCompaction) null else current.compaction,
        contextUsed = if (invalidatesCompaction) 0 else current.contextUsed,
    )

    fun rolledBack(current: ChatUiState): ChatUiState = current.copy(
        transcript = before.transcript,
        compaction = before.compaction,
        contextUsed = before.contextUsed,
        isGenerating = false,
        error = STORAGE_FAILED,
    )
}

/** The transcript without a trailing reply that never said anything. See [TranscriptEntry.saidNothing]. */
private fun List<TranscriptEntry>.withoutEmptyReply(): List<TranscriptEntry> =
    if (lastOrNull()?.saidNothing == true) dropLast(1) else this

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
    /** What the loaded model writes, which decides which settings are worth offering. */
    val outputModality: OutputModality = OutputModality.TEXT,
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
    /**
     * What earlier conversations left behind, already rendered, or null when memory is off
     * or empty.
     *
     * Rendered upstream rather than read here so that [engineMessages] stays a function of
     * its state and can be tested without a store behind it.
     */
    val memories: String? = null,
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

    /**
     * Whether the composer may start a turn.
     *
     * [isCompacting] belongs here and was missing, which is a race rather than a nicety.
     * Folding runs after a reply, while the user is reading it, and it runs the model for
     * twenty to thirty seconds. Send during that window passed this check, reached
     * [ChatViewModel.generate], found `Folding` already busy and skipped its own fold, and
     * then called the engine while the summary was still streaming into it. Two turns on one
     * engine: the single-threaded executor keeps it from corrupting anything, and what comes
     * out is a turn that re-reads the whole conversation because the cache holds the summary
     * prompt, answered from a history the screen has already replaced. Nothing crashes and
     * nothing is right.
     */
    val canSend: Boolean get() =
        modelName != null &&
            outputModality == OutputModality.TEXT &&
            !isGenerating &&
            !isLoadingModel &&
            !isCompacting
}

@HiltViewModel
// Owed work, named rather than waved away. This class is the chat screen, the turn loop and
// the goal loop in one, and ten dependencies is what carrying all three costs. The seam is
// the goal loop: `startGoal` through `stepPrompt` is fourteen functions that talk to the
// boards, the runtime and one turn at a time, and lifting them out takes this back under
// every one of these limits at once. It is not lifted here because it needs a callback
// surface back into the turn machinery, which is a design decision rather than a lint fix,
// and because the tests that make it safe to move only landed alongside this comment.
@Suppress("LongParameterList", "LargeClass", "TooManyFunctions")
class ChatViewModel @Inject constructor(
    private val runtime: ModelRuntime,
    private val compactor: ConversationCompactor,
    private val staging: Staging,
    private val writer: ChatWriter,
    private val turns: TurnRunner,
    private val notifier: ReplyNotifier,
    private val goals: GoalBoard,
    private val memory: Memory,
    @param:ApplicationContext private val appContext: Context,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    /** Completed by the approval buttons, so the agent can wait on a human. */
    private var approval: CompletableDeferred<Boolean>? = null

    /**
     * One question on screen at a time.
     *
     * There is a single [approval] slot and a single card above the composer, so two
     * questions raised at once would leave the first waiting on a deferred that has been
     * replaced and can no longer be completed: the turn hangs until Stop. Tools that run
     * together are chosen so this does not arise, and this keeps the failure at "asked one
     * after the other" rather than "never answered" if that ever changes.
     */
    private val approvalGate = Mutex()

    /** Tool evidence emitted by the current goal turn; reset before every turn starts. */
    private var lastTurnSteps: List<AgentStep> = emptyList()
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * What is attached to the message being typed.
     *
     * Built here rather than injected, because it needs this view model's scope and its state,
     * and a Hilt binding for something that takes both would be a longer way to say the same.
     */
    private val attachments = Attaching(
        staging = staging,
        scope = viewModelScope,
        state = _uiState,
        limitMessage = appContext.getString(R.string.attachment_limit, MAX_STAGED_ATTACHMENTS),
        unreadableMessage = appContext.getString(R.string.attachment_unreadable),
    )

    /** Folding older turns into a summary. Built here for the reason [attachments] is. */
    private val folding = Folding(compactor, writer, _uiState)

    private var generationJob: Job? = null
    private var thermalJob: Job? = null
    private var goalJob: Job? = null

    /**
     * Overrides whether the next turn offers ask_user, consumed the moment [generate] reads
     * it so it cannot leak into the turn after the one it was set for.
     *
     * Set immediately before the one turn that needs it — a research brief's own planning
     * turn — rather than threaded as a parameter through [send], which every ordinary
     * message also calls and has no reason to know this exists.
     */
    private var offerAskOverride: Boolean? = null

    /**
     * Overrides the configured tool prompt for the next turn only, consumed by [openTurn]
     * the same way [offerAskOverride] is consumed by [generate].
     *
     * Set before a research step. The configured prompt defaults to "you already know the
     * answer to most questions", tuned for ordinary chat where a needless search is the
     * failure worth avoiding; a research step is already past that decision; the plan
     * would not have this question on it if the answer were assumed known. Sent as-is that
     * instruction argues with the very turn asking the model to search.
     */
    private var toolPromptOverride: String? = null

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

    /** Explicitly releases the loaded model while keeping its files available to reload. */
    fun unloadModel() {
        stop()
        val request = ++loadRequest
        viewModelScope.launch {
            generationJob?.join()
            loadMutex.withLock {
                if (request != loadRequest) return@withLock
                runCatching {
                    runtime.unload()
                    forgetLoadedModel()
                    preferencesKey = null
                }.onFailure { failure ->
                    _uiState.update { it.copy(error = failure.userMessage()) }
                }
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
                outputModality = OutputModality.TEXT,
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
                    error = if (keepConversation) {
                        it.transcript.unreadableWarning(support)
                    } else {
                        null
                    },
                ).withCapabilities(info, runtime.ignoresThinkingSwitch(modelFile.name))
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
                            .also {
                                conversationId = it
                                // A goal or research started on a still-empty chat had no
                                // conversation to record when it started; this is the first
                                // moment one exists. See GoalBoard.bindConversation.
                                goals.bindConversation(it)
                            }
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

    /** Several at once, from a multi-select picker or from the clipboard. */
    fun attachAll(uris: List<Uri>) = attachments.attachAll(uris)

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
     * Rewrites a question that was already asked, and answers the new one.
     *
     * The conversation ends at the edited turn. Everything the old wording produced, and
     * everything said after it, is dropped from the screen and from storage before the new
     * question is sent, because a transcript holding both is a conversation the user never
     * had and the model would read it as one.
     *
     * Nothing here touches the KV cache on purpose. The engine compares the new prompt
     * against the tokens it is holding and keeps the longest common prefix, which is
     * everything before the edited turn; on a hybrid model, where a partial rollback is
     * refused, it starts over instead. Either way the cache is a function of the prompt, and
     * a second mechanism reaching in to purge it could only disagree with the first.
     */
    fun editAndResend(entryId: Long, text: String) {
        val edit = editFor(entryId, text) ?: return
        _uiState.update { edit.applied(it) }

        val editJob = viewModelScope.launch {
            if (!rewriteStoredTurn(edit)) {
                _uiState.update { edit.rolledBack(it) }
                return@launch
            }
            _uiState.update { current ->
                current.copy(
                    transcript = edit.kept +
                        entry(ChatRole.USER, edit.text).copy(attachments = edit.attachments),
                    isGenerating = true,
                )
            }
            attachments.discard(edit.abandoned)
            generate()
        }
        generationJob = editJob
        // The same guard [send] carries, and for the same reason. This claims the busy
        // state before the rewrite, and the rewrite waits on the write queue, which can sit
        // behind a compaction. Stop in that window cancels the job before generate() has
        // run, so nothing has registered `releaseTurn` and nothing else hands the state
        // back: the composer stayed disabled for the rest of the session.
        editJob.invokeOnCompletion { cause ->
            if (cause != null && generationJob === editJob) {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    /**
     * Works out everything one edit changes, or returns null if it changes nothing.
     *
     * Decided in one place and off a single read of the state, because the screen keeps
     * moving: tokens arrive, a fold completes, and a rule read at the top would no longer
     * hold by the time the write at the bottom ran. What comes back is a description of the
     * edit rather than the edit itself, so applying it, undoing it, and writing it down are
     * three readings of the same decision instead of three decisions.
     */
    private fun editFor(entryId: Long, text: String): Edit? {
        val edited = text.trim()
        val state = _uiState.value
        val at = state.transcript.indexOfFirst { it.id == entryId }
        // Only a question of the user's own, and only while nothing else is rewriting the
        // transcript. `loadedModelHasGone` is last because it is the one that also puts a
        // message on screen, and there is nothing to say about a missing model to somebody
        // who is not going to get this far anyway.
        val editable = at >= 0 && state.transcript[at].role == ChatRole.USER
        val busy = state.isGenerating || state.isCompacting || loadedModelHasGone()
        if (edited.isEmpty() || !editable || busy) return null

        // The summary covered the turn being rewritten, so it describes a conversation that
        // is about to stop existing and has to go with it.
        val invalidatesCompaction = state.compaction?.foldedThroughIndex?.let { at <= it } == true
        return Edit(
            at = at,
            text = edited,
            attachments = state.transcript[at].attachments,
            abandoned = state.transcript.drop(at + 1).flatMap { it.attachments },
            kept = state.transcript.take(at).map {
                if (invalidatesCompaction) it.copy(compactionNote = null) else it
            },
            invalidatesCompaction = invalidatesCompaction,
            before = state,
        )
    }

    /**
     * Puts the rewritten turn in storage, and says whether it got there.
     *
     * Reported rather than thrown, because the screen has already been changed and the two
     * have to agree: a transcript that shows the edit while storage still holds the original
     * is a conversation that changes back the next time it is opened.
     */
    private suspend fun rewriteStoredTurn(edit: Edit): Boolean {
        val written = writeOrNull {
            writer.inOrder {
                conversationId?.let { id ->
                    // By position rather than by id: the transcript's ids are the app's
                    // and the stored rows have their own, and the two only line up
                    // while nothing has been dropped from either.
                    messages(id).getOrNull(edit.at)?.let { message ->
                        replaceFrom(
                            conversationId = id,
                            messageId = message.id,
                            text = edit.text,
                            attachments = edit.attachments,
                            clearCompaction = edit.invalidatesCompaction,
                        )
                    }
                }
            }
        }
        return written != null
    }

    /**
     * Carries this conversation up to a point into a new one, and opens it there.
     *
     * For the moment somebody wants to try a different direction without losing the one they
     * are on. The alternative people actually use is to scroll up, copy their question and
     * paste it into a new chat, which loses everything before it.
     *
     * Copied rather than shared. Two conversations pointing at the same rows would diverge
     * the moment either was folded, and a summary written for one would appear in the other
     * describing turns it does not have.
     */
    fun branchFrom(entryId: Long) {
        val state = _uiState.value
        if (state.isGenerating || state.isCompacting) return
        val upTo = state.transcript.indexOfFirst { it.id == entryId }
        if (upTo < 0) return
        val carried = state.transcript.take(upTo + 1)
        if (carried.isEmpty()) return

        viewModelScope.launch {
            val copiedTranscript = mutableListOf<TranscriptEntry>()
            val copiedFiles = mutableListOf<MessagePart.File>()
            var createdId: Long? = null
            val branched = writeOrNull {
                writer.inOrder {
                    val title = carried.first { it.role == ChatRole.USER }.text
                    val id = startConversation(title, state.modelName).also { createdId = it }
                    // Skipped rather than carried, because the summary belongs to the
                    // conversation it was written for: the new one has every turn the old
                    // one folded away, so there is nothing for it to stand in for.
                    carried.forEach { entry ->
                        val attachments = staging.duplicate(entry.attachments)
                        copiedFiles += attachments
                        copiedTranscript += entry.copy(attachments = attachments)
                        addMessage(
                            conversationId = id,
                            role = entry.role.wireName,
                            text = entry.history ?: entry.text,
                            attachments = attachments,
                        )
                    }
                    id
                }
            }

            if (branched == null) {
                cleanUpBranch(createdId, copiedFiles)
                reportError(STORAGE_FAILED)
                return@launch
            }
            // Straight over, with the cache cleared: the new conversation shares a prefix
            // with the old one but the engine is about to be asked about a different
            // conversation id, and leaving a stale cache to be matched against is how a
            // reply ends up answering the chat somebody just left.
            runtime.resetContext()
            conversationId = branched
            _uiState.update {
                it.copy(
                    transcript = copiedTranscript,
                    compaction = null,
                    toolNotes = ToolNotes(),
                    contextUsed = 0,
                    error = null,
                )
            }
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
    /**
     * Removes what a branch that did not finish left behind.
     *
     * Uncancellable on purpose, and that is the whole reason it is its own function. This
     * runs on the failure path, and one of the ways to reach the failure path is the
     * coroutine being cancelled: cleanup written inline suspends at its first line, is
     * cancelled there, and does nothing. What it does not do is delete a conversation that
     * has already been created and filled in, so a branch stopped halfway stayed in the
     * drawer, with copies of every attachment it had got through.
     */
    private suspend fun cleanUpBranch(createdId: Long?, copied: List<MessagePart.File>) {
        withContext(NonCancellable) {
            writeOrNull { writer.inOrder { createdId?.let { deleteConversation(it) } } }
            runCatching { staging.discard(copied) }
        }
    }

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

    /**
     * Everything that has to be true before the engine is asked anything.
     *
     * Returns what the turn was opened on, or null when there is nothing to send and the
     * turn is over before it started. Separated from [generate] because these are decisions
     * about the conversation while what follows is a stream of tokens, and reading one while
     * looking for the other is how the empty-conversation branch came to be easy to miss.
     */
    private suspend fun openTurn(): OpenedTurn? {
        // Folded before the turn as well as after it. Compaction only ever ran at the
        // end, so a conversation already past the threshold started its next turn
        // against the wall, and what the user saw was the turn failing rather than the
        // history being folded. Cheap when there is nothing to fold.
        compactIfNeeded()

        // Read here rather than held: the user can switch a tool off in another tab
        // between one question and the next, and the instruction has to follow. Memory
        // is read in the same breath and for the same reason, and only when its own
        // tool is on: switching it off has to stop the app remembering and stop it
        // repeating what it already remembered.
        _uiState.update {
            it.copy(
                toolsAvailable = turns.hasEnabledTools(),
                memories = memory.asPrompt().takeIf { _ -> turns.remembers() },
            )
        }

        val state = _uiState.value
        val conversation = state.engineMessages(
            toolPromptOverride = toolPromptOverride.also { toolPromptOverride = null },
        )
        if (conversation.isEmpty()) {
            // Callers claim the busy state before this point to close the double-tap
            // window, so nothing to send has to give it back.
            _uiState.update { it.copy(isGenerating = false) }
            return null
        }

        _uiState.update { current ->
            current.copy(
                transcript = current.transcript +
                    entry(ChatRole.ASSISTANT, "").copy(isStreaming = true),
                isGenerating = true,
            )
        }
        return OpenedTurn(conversation = conversation, state = state)
    }

    private fun generate() {
        startThermalSampling()
        // Before the work, because a foreground service cannot be started from the
        // background and the tap that got here was the foreground. See GenerationService:
        // without it, leaving the app during a reply stops the reply dead.
        GenerationService.hold(appContext, GenerationService.TURN, "Answering")

        val job = viewModelScope.launch {
            val opened = openTurn() ?: return@launch
            val conversation = opened.conversation
            val state = opened.state

            val turnStartedAt = System.currentTimeMillis()
            var lastFrameAt = 0L
            var reasoningEndedAt: Long? = null
            var produced = ""
            // Set once, at the one place a completed turn is persisted, and read again by
            // settleTurn below rather than recomputed: two separate System.currentTimeMillis()
            // calls a few suspension points apart do not agree to the millisecond, and the
            // live transcript entry disagreeing with the row just written under it read as a
            // reopened conversation losing track of its own number.
            var settledMillis: Long? = null

            // What the turn will write down, replaced by each pass that completes, so what
            // survives is the pass the turn ended on rather than the one that asked for a
            // tool. Null until a pass completes at all, which is what tells the difference
            // between a turn that answered and one that was stopped before it could.
            var settled: Pair<String, GenerationStats>? = null
            lastTurnSteps = emptyList()

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
                    lastTurnSteps += steps
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
                // Consumed here and only here: whatever this turn was set to override, the
                // next one starts with nothing overridden unless it sets its own.
                val offerAsk = offerAskOverride.also { offerAskOverride = null }
                produced = turns.run(
                    conversation = conversation,
                    params = state.preferences.toSamplerParams().let {
                        if (state.toolsAvailable) it.copy(thinking = true) else it
                    },
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
                    offerAsk = offerAsk,
                )
                // Here, so a turn that used a tool is written down once. Skipped by both
                // catches below, where finishInterrupted writes what was produced instead.
                // Blank is not written at all: an empty row reopens as an empty bubble and
                // is resent as an empty assistant turn, which some templates refuse.
                settled
                    ?.takeIf { (text, _) -> text.isNotBlank() }
                    ?.let { (text, stats) ->
                        settledMillis = System.currentTimeMillis() - turnStartedAt
                        persistReply(text, stats, settledMillis)
                    }
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

            settleTurn(produced, turnStartedAt, settledMillis)
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
    private suspend fun settleTurn(raw: String, turnStartedAt: Long, settledMillis: Long?) {
        // A stream that ends without a completion event, which happens if the engine's
        // channel closes under it, would otherwise leave the entry streaming forever.
        if (_uiState.value.transcript.lastOrNull()?.isStreaming == true) {
            finishInterrupted(raw)
        }

        _uiState.update { it.droppingEmptyReply() ?: it }
        // The same value just persisted, when there is one, rather than a second reading of
        // the clock: a completed turn's row and its live entry must agree to the millisecond,
        // or a reopened conversation shows a different number than the one it just showed.
        updateLastEntry {
            it.copy(totalMillis = settledMillis ?: (System.currentTimeMillis() - turnStartedAt))
        }
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
        // Here rather than at the end of the body, for the reason this function exists:
        // two of the three ways a turn ends never reach the last line, and a notification
        // left up for work that stopped is worse than none.
        //
        // Releases the turn's hold and not the goal's. A goal is many turns and does its own
        // work in the gaps between them; dropping the process there is what let it freeze
        // mid-goal.
        GenerationService.release(appContext, GenerationService.TURN)
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
    private suspend fun askUser(call: ToolCall): Boolean = approvalGate.withLock {
        val pending = CompletableDeferred<Boolean>()
        approval = pending
        _uiState.update { it.copy(pendingApproval = call) }
        try {
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

    /** What the goal is doing, for the screen to show and the user to stop. */
    val goal: StateFlow<Goal?> get() = goals.goal

    fun setMode(mode: AgentMode) = _uiState.update { it.copy(mode = mode) }

    /**
     * Works through a task without being asked again, until it is done or a bound stops it.
     *
     * Each step is an ordinary turn. That is the whole design: the step goes through `send`
     * like anything the user typed, so it is written to the conversation, folded when the
     * context fills, shown on screen as it streams and cancelled by the same Stop. A runner
     * that drove the engine directly would have to reimplement all of that and would drift
     * from it.
     *
     * The first turn is plan mode, which since it was restricted to the two planning tools
     * runs nothing and asks when the request is ambiguous. Everything after it is the mode
     * the user was already in.
     */
    fun startGoal(task: String) = start(task, Brief.GOAL)

    /**
     * Researching a question, which is a goal with three differences.
     *
     * It plans questions rather than actions, every step is expected to search rather than
     * allowed to, and it ends with a turn that writes the findings up. That last one is the
     * whole point: a goal reports on each step and stops, and a pile of step reports is not
     * a piece of research.
     *
     * Reusing the goal loop rather than writing a second one is deliberate. Everything that
     * makes unattended work safe on a phone already lives there: the foreground service
     * across step boundaries, the engine lock, the thermal and battery checks between steps,
     * the consecutive-failure limit and the stop button. A parallel implementation would
     * have to grow every one of those again and would grow them a little differently.
     */
    fun startResearch(question: String) = start(question, Brief.RESEARCH)

    private fun start(task: String, brief: Brief) {
        if (task.isBlank() || goals.isRunning) return
        if (!_uiState.value.canSend) {
            _uiState.value.refusalReason()?.let { why -> reportError(why) }
            return
        }
        // A plan left over from whatever this conversation last ran through the loop —
        // finished, stopped, or halted, none of which touch the board on their own. Without
        // this a new goal's planning turn that answered in prose rather than a plan read as
        // having one anyway: the old plan was still sitting there, non-null, so the check for
        // "did a plan come back" passed on a plan this goal never proposed and could go on to
        // run steps that belonged to the last one.
        turns.planning.clear()
        goals.start(task, conversationId)
        // Held across the whole goal, including the gaps between steps where the turn's own
        // hold is not in force. Released in work()'s finally, whatever ends it.
        GenerationService.hold(appContext, GenerationService.GOAL, brief.notification)
        goalJob = viewModelScope.launch { work(task, brief) }
    }

    /** Always allowed, and the only control a goal needs to offer. */
    fun stopGoal() {
        goals.stop()
        goalJob?.cancel()
        stop()
        // A goal stopped mid-question leaves ask_user suspended on nothing worth resuming:
        // the turn it belonged to is not going to finish either way. Without this the
        // question card outlived the goal it came from, asking on behalf of a run that had
        // already ended.
        turns.asking.cancel()
        // Set for the turn a cancelled job never reached: consumed once by the turn it was
        // meant for, and nothing else clears it if that turn was the one just cancelled. Left
        // set, it would apply itself to whatever ordinary turn runs next.
        offerAskOverride = null
        toolPromptOverride = null
    }

    /**
     * What the user typed while it was running, kept for the next step.
     *
     * Not sent as a turn of its own. A goal reading a message the moment it arrives would
     * have to interrupt a turn already streaming; holding it until the step boundary is the
     * first point where reading it cannot corrupt anything.
     */
    fun steerGoal(message: String) = goals.steer(message)

    /** Removes a finished, stopped, or recovered goal card from the conversation UI. */
    fun dismissGoal() {
        if (goals.goal.value?.isRunning != true) goals.clear()
    }

    private suspend fun work(task: String, brief: Brief) {
        val wasMode = _uiState.value.mode
        var failures = 0
        val researchSources = linkedSetOf<String>()
        try {
            setMode(AgentMode.PLAN)
            offerAskOverride = if (brief.offersAskDuringPlan) null else false
            if (!turn("${brief.plan}\n\n$task")) return
            // Falls back for a brief that has one: measured live, a small model asked to
            // plan a subject it does not recognise sometimes answers in prose instead of
            // proposing anything at all, tool or no tool. Research's own fallback plans
            // one step, the question as asked, rather than stopping before it searched
            // once. See Brief.RESEARCH.
            val proposed = turns.planning.plan.value
                ?: brief.fallbackPlan(task)?.also { turns.planning.restore(it) }
            if (proposed == null || proposed.steps.isEmpty()) {
                goals.halt("No plan came back, so there is nothing to work through.")
                return
            }
            goals.planned(proposed)

            // Research is an explicitly autonomous operation. AUTO keeps web tools
            // available without inheriting PLAN, stopping for every call in ASK, or
            // inheriting YOLO's waived egress checks.
            // PLAN is only for producing the plan. Leaving a generic goal in PLAN would
            // silently strip every tool from all execution turns, so a goal started from
            // the planning UI could never carry out its own steps.
            setMode(goalExecutionMode(brief.executionMode, wasMode))
            var running = true
            while (running) {
                when (step(proposed, failures, brief, researchSources)) {
                    StepOutcome.DONE -> failures = 0
                    StepOutcome.RETRY -> failures++
                    StepOutcome.STOP -> running = false
                }
            }
            writeUp(brief, researchSources)
        } finally {
            // Not simply `wasMode`. Starting from PLAN is exactly the case this app has to
            // handle, since PLAN is where a goal's own plan comes from, and restoring it
            // verbatim would return the user to the one mode with no tools in it, with
            // nothing on screen saying why running a goal changed nothing. Same rule as
            // starting one: see goalExecutionMode.
            setMode(goalExecutionMode(null, wasMode))
            GenerationService.release(appContext, GenerationService.GOAL)
        }
    }

    /**
     * One last turn, for work whose answer is not the last step but everything the steps
     * found.
     *
     * Only when the plan actually finished. A run that was stopped by the user, halted for
     * heat, or gave up after three failures has nothing to write up, and writing it up
     * anyway would produce a confident report from half the research, which is the worst
     * possible output of a research tool.
     */
    private suspend fun writeUp(brief: Brief, researchSources: Set<String>) {
        val finish = brief.finish ?: return
        if (goals.goal.value?.state != GoalState.DONE) return
        val sourceBoundary = if (brief.requiresWebEvidence) {
            "\n\nThese are the only source addresses the app verified were read:\n" +
                researchSources.joinToString(separator = "\n") { "- $it" }
        } else {
            ""
        }
        turn(finish + sourceBoundary)
    }

    /**
     * One step of a goal: check it may run, run it, and decide what the result means.
     *
     * Split out of the loop rather than left inline because a loop body with four ways to
     * stop in it stops being readable as a loop. The three outcomes are the whole contract.
     */
    private suspend fun step(
        proposed: TaskPlan,
        failures: Int,
        brief: Brief,
        researchSources: MutableSet<String>,
    ): StepOutcome {
        val goal = goals.goal.value
        val step = goal?.currentStep
        if (goal?.isRunning != true || step == null) return StepOutcome.STOP
        tooHotOrFlat()?.let {
            goals.halt(it)
            return StepOutcome.STOP
        }

        val steering = goals.takeSteering()
        val planBefore = turns.planning.plan.value ?: proposed
        val doneBefore = planBefore.steps.count { it.done }
        _uiState.update { it.copy(error = null) }
        // The configured tool prompt defaults to "you already know the answer to most
        // questions", which is right for ordinary chat and argues with the one turn whose
        // entire purpose is searching: this step exists because the plan already decided
        // the answer was not already known. See RESEARCH_STEP_TOOL_PROMPT.
        if (brief.requiresWebEvidence) toolPromptOverride = RESEARCH_STEP_TOOL_PROMPT
        if (!turn(stepPrompt(brief, step.text, steering))) return StepOutcome.STOP

        val verifiedSources = lastTurnSteps.correlatedWebResearchSources()
        // What the turn is pointed at is exactly one step, but nothing before this stopped
        // the model calling `advance` more than once in the same turn — several sequential
        // tool calls are one round to AgentRunner, and each one ticks whichever step it
        // names. A single turn could finish the whole plan at once and still pass the
        // evidence check above, which only asks whether some step was researched, not
        // which one or how many.
        val planAfter = turns.planning.plan.value ?: proposed
        val stepIndex = planBefore.steps.indexOfFirst { !it.done }
        val newlyDone = planAfter.steps.indices.filter { i ->
            planAfter.steps[i].done && planBefore.steps.getOrNull(i)?.done != true
        }
        val skippedAhead = newlyDone.isNotEmpty() && newlyDone != listOf(stepIndex)
        // Calling `advance` on a step that was already done closes nothing: `newlyDone` stays
        // empty, exactly as if the model had not called it at all, and the branch below would
        // read that as silence and tick the *next* step on the model's behalf with no evidence
        // it was worked on — the same failure `skippedAhead` exists to catch, reached from the
        // other direction. A stale or wrong step number is treated the same as too many.
        val calledAdvance = lastTurnSteps.any { it is AgentStep.Ran && it.call.name == "advance" }
        val advancedNothing = calledAdvance && newlyDone.isEmpty()
        // A step that tried a tool and every one of them failed or was declined is not
        // evidence of work done, and `tickIfTheModelDidNot` cannot tell that from a step
        // that needed no tool at all: both leave `doneBefore` unchanged. Checked only when
        // the model did not call `advance` itself, since a step that closed itself despite a
        // failed tool call is judged by whether it named the right step, not by this.
        val allToolsFailed = !calledAdvance &&
            lastTurnSteps.any { it is AgentStep.Ran || it is AgentStep.Skipped } &&
            lastTurnSteps.none { it is AgentStep.Ran && it.successful }

        when {
            brief.requiresWebEvidence && verifiedSources.isEmpty() -> {
                // Undo an eager `advance` call before retrying. A model saying it searched
                // is not evidence that a source was actually reached.
                turns.planning.restore(planBefore)
                _uiState.update {
                    it.copy(
                        error = "This research step did not successfully search and read " +
                            "a source, so it was not marked done.",
                    )
                }
            }

            skippedAhead -> {
                turns.planning.restore(planBefore)
                _uiState.update {
                    it.copy(
                        error = "This step closed more than the one it was given, so it " +
                            "was not marked done.",
                    )
                }
            }

            advancedNothing -> {
                turns.planning.restore(planBefore)
                _uiState.update {
                    it.copy(
                        error = "This step's advance call did not close the step it was " +
                            "given, so it was not marked done.",
                    )
                }
            }

            allToolsFailed -> {
                turns.planning.restore(planBefore)
                _uiState.update {
                    it.copy(
                        error = "This step's tool calls did not succeed, so it was not " +
                            "marked done.",
                    )
                }
            }

            else -> researchSources += verifiedSources
        }
        val failure = _uiState.value.error
        return if (failure != null) {
            // A step that ended in an error has not been done, whatever the plan says. One
            // retry, because the common failure is a tool call the model can repair once it
            // reads the message, and then a halt: a loop that retries forever on a phone is
            // a flat battery rather than an answer.
            if (failures + 1 >= MAX_STEP_FAILURES) {
                goals.halt(
                    "Stopped after $MAX_STEP_FAILURES steps in a row that did not finish. " +
                        "The last problem was: $failure",
                )
                StepOutcome.STOP
            } else {
                StepOutcome.RETRY
            }
        } else {
            tickIfTheModelDidNot(doneBefore)
            goals.advanced(turns.planning.plan.value ?: proposed)
            StepOutcome.DONE
        }
    }

    /**
     * Closes the step just finished, unless the model closed it itself.
     *
     * Ticked by the app rather than by the model, because asking a 2.6B whether it succeeded
     * is asking the thing that just failed to mark its own work, and a goal that never ticks
     * never ends.
     *
     * The condition is the bug this replaces. `advance` is a tool the model can call, and
     * when it did, the host went looking for the first unfinished step and found the *next*
     * one, so a single turn closed two. A four step plan could report itself finished having
     * done half of it, and the step it skipped was as likely to be "check the result" as
     * anything else.
     *
     * @param doneBefore how many steps were closed before the turn ran.
     */
    private fun tickIfTheModelDidNot(doneBefore: Int) {
        val steps = turns.planning.plan.value?.steps ?: return
        if (steps.count { it.done } != doneBefore) return
        val index = steps.indexOfFirst { !it.done }
        if (index >= 0) turns.planning.tick(index)
    }

    /** One step, run and waited for. False when it did not run at all. */
    private suspend fun turn(prompt: String): Boolean {
        if (!send(prompt)) {
            goals.halt(_uiState.value.error ?: "The turn could not be started.")
            return false
        }
        awaitTurn()
        return goals.goal.value?.isRunning == true
    }

    /**
     * Waits for the turn to finish, rather than for it to have started.
     *
     * [send] registers a short job of its own that writes the question down and then calls
     * [generate], which replaces `generationJob` with the one that actually produces the
     * reply. Joining once joined whichever of the two happened to be current at that
     * instant, which is almost always the first, so the goal loop came back the moment the
     * turn had begun.
     *
     * What that looked like was not a race that sometimes lost. The loop read the plan
     * board before the planning turn could put anything on it, found nothing, and halted
     * with "No plan came back"; and where a plan was already there the next step called
     * [send] while the previous turn was still generating, was refused, and halted saying
     * the turn could not be started. A goal on a real phone, where a reply takes seconds,
     * lost this every time.
     *
     * So: join, and if something replaced the job while waiting, join that too.
     */
    private suspend fun awaitTurn() {
        while (true) {
            val job = generationJob ?: return
            job.join()
            if (generationJob === job) return
        }
    }

    /**
     * Whether the phone can take another step.
     *
     * Checked between steps rather than during one, because stopping halfway through a
     * reply wastes the whole step. Heat first: sustained decode was measured falling from
     * about 25 to about 19 tokens a second over three and a half minutes, and a goal is the
     * one thing here that runs long enough to reach the top of that curve.
     */
    private fun tooHotOrFlat(): String? {
        if (runtime.thermalLevel() == ThermalLevel.CRITICAL) {
            return "Paused: the phone is too hot to keep going. Start it again when it has " +
                "cooled down."
        }
        val battery = appContext.getSystemService<BatteryManager>()
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return null
        if (battery in 1..<MIN_BATTERY_PERCENT) {
            return "Paused at $battery% battery. Working on its own uses a lot of it, so " +
                "this stops rather than flattening the phone."
        }
        return null
    }

    private fun stepPrompt(brief: Brief, step: String, steering: List<String>): String =
        buildString {
            append(brief.step)
            append("\n\n")
            append(step)
            if (steering.isNotEmpty()) {
                append("\n\nSince you started, I have said: ")
                append(steering.joinToString(" "))
            }
        }

    /**
     * Re-plans threads for this reply, and says whether to go ahead.
     *
     * The decision to stop rather than to stop slightly less lives in the runtime; what
     * lives here is what the user is told when it does.
     */
    private suspend fun applyThreadPlan(): Boolean {
        _uiState.update { it.copy(isThrottled = runtime.isThrottling()) }
        if (runtime.planThreads()) return true

        _uiState.update { state ->
            state.copy(
                transcript = state.transcript.withoutEmptyReply(),
                isGenerating = false,
                error = "The phone is too hot to keep generating. It will work again " +
                    "once it cools down.",
            )
        }
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
        // A goal is driven by its own job, separate from generationJob, and reading the
        // board rather than the transcript to decide what to do next: stopping only the
        // turn in flight left it free to join a wait, see the board still says running, and
        // start the next step against whatever conversation happens to be open by the time
        // it wakes — this one, mid-switch. stopGoal ends the loop itself, not just its turn.
        if (goals.goal.value?.isRunning == true) stopGoal() else stop()
        viewModelScope.launch {
            // Awaited, not merely cancelled: a generation still unwinding writes into the
            // transcript this is about to replace, and would do so after the engine cache
            // has already been reset underneath it.
            generationJob?.join()
            // The same reasoning as generationJob, for the goal's own loop: cancel() only
            // requests it stop, and work() still unwinding past that request can go on to
            // read or write the very board goals.clear() below is about to reset, or the
            // planning board turns.planning.clear() is.
            goalJob?.join()
            runtime.resetContext()
            conversationId = null
            // A research turn interrupted mid-plan can leave these set; unconsumed, the
            // new chat's first turn would silently inherit an override that belonged to
            // the conversation just left behind. See start().
            offerAskOverride = null
            toolPromptOverride = null
            turns.planning.clear()
            // The board is one object for the whole app: a goal left over from the chat just
            // left behind would otherwise be on screen here too, its card naming a task this
            // conversation never asked for. Safe to clear unconditionally now that a running
            // one has already been stopped above.
            goals.clear()
            turns.asking.cancel()
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

        // See newChat: stopping only the turn in flight leaves a goal's own loop free to
        // read the board, find itself still running, and start its next step against
        // whichever conversation is open by the time it wakes — this one, mid-switch. Not
        // when the running goal already belongs to the conversation being reopened, though:
        // that is the same case the cleanup below carves out, and stopping a goal because
        // someone reopened the very chat it is already working in would be a switch that
        // never happened.
        val runningElsewhere = goals.goal.value?.isRunning == true &&
            goals.goal.value?.conversationId != id
        if (runningElsewhere) stopGoal() else stop()
        generationJob?.join()
        // Same reasoning as newChat: cancel() only asks work() to stop, and it unwinding
        // past that request must not go on to touch the board this function is about to
        // read and clear for the conversation being opened.
        if (runningElsewhere) goalJob?.join()

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
        //
        // Not when the goal on the board is tagged with the very conversation being opened,
        // though: that is recovery, not a switch. A goal interrupted by the process dying
        // comes back HALTED so a person can review it, and reopening its own conversation on
        // the next launch — which restoring the last chat does automatically — used to read
        // as leaving it behind and clear it before anyone had a chance to see it come back.
        if (goals.goal.value?.conversationId != id) {
            turns.planning.clear()
            // Same reasoning: a goal (and any question it left pending) belongs to the
            // conversation that started it, not to whichever one is opened next. Safe to
            // clear unconditionally here — a still-running one has already been stopped
            // above — because this branch is only reached when it belongs elsewhere.
            goals.clear()
            turns.asking.cancel()
        }

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

        // After the transcript is on screen, not before. This only matters for the next
        // reply's KV cache, and the engine call behind it shares a single-threaded
        // dispatcher with model loading: reopening a chat while a model is still loading
        // used to sit here waiting its turn on that thread, so a previous conversation's
        // history stayed hidden behind the loading screen for exactly as long as the model
        // took to load, instead of appearing immediately from data already in hand.
        runtime.resetContext()
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
                promptTokens = event.stats.totalPromptTokens,
                cachedTokens = event.stats.cachedTokens,
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
    private suspend fun persistReply(
        text: String,
        stats: GenerationStats?,
        totalMillis: Long? = null,
    ) {
        val id = conversationId ?: return
        val reasoningMs = _uiState.value.transcript.lastOrNull()?.reasoningMs
        withContext(NonCancellable) {
            reportingFailure { writer.reply(id, text, stats, reasoningMs, totalMillis) }
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
/**
 * Everything the loaded model turned out to be able to do, folded on in one place.
 *
 * Five fields interrogating the same nullable, which read as five null checks in the middle
 * of a function whose subject is opening a file. Kept top level rather than made a method so
 * that answering "what can this model do" does not enlarge the view model.
 */
private fun ChatUiState.withCapabilities(
    info: LoadedModelInfo?,
    /** Whether this model has already been caught generating a chain of thought anyway. */
    ignoresThinkingSwitch: Boolean,
) = copy(
    mediaSupport = info?.mediaSupport ?: MediaSupport(),
    outputModality = info?.outputModality ?: OutputModality.TEXT,
    // Two hurdles, and a model has to clear both. The template must render differently when
    // told not to think, and the weights must not have been caught ignoring that already.
    supportsThinking = info?.supportsThinking == true && !ignoresThinkingSwitch,
    supportsTools = info?.supportsTools == true,
    supportsReasoningEffort = info?.supportsReasoningEffort == true,
)

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
    if (writeOrNull(write) == null) report(STORAGE_FAILED)
}

/**
 * Runs a write and gives back what it produced, or null if it would not go through.
 *
 * The same contract as [writeReportingFailure] for callers that need the value rather than
 * only the outcome, and it exists because `runCatching` is not that contract. `runCatching`
 * catches a cancellation exactly like a failure, so pressing Stop over a write became a
 * write that failed: the edit that was being saved reported that storage was broken, and
 * the branch that was being copied reported the same and left the half-built conversation
 * it had already created sitting in the drawer.
 *
 * Cancellation is passed on. Everything else is logged and answered with null.
 */
internal suspend fun <T> writeOrNull(write: suspend () -> T): T? = try {
    write()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
    Log.w("OpenWeights", "a chat write did not go through", failure)
    null
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
/**
 * The answer style now lives in [AnswerLength], because it became a setting.
 *
 * It used to be a constant here reading "in a few sentences", which capped every answer
 * including the ones that asked for the opposite: the 1.2B refused five paragraphs outright,
 * blaming the tools, and the 2.6B quietly wrote a third of what it would have. The wording
 * that replaced it is Balanced, and the other two are the control that was missing.
 */

/**
 * That the answer may be formatted, which the model does not assume.
 *
 * The app has rendered Markdown for as long as it has rendered replies, and the models were
 * not producing any. Measured on four questions that invite structure, with no tools in the
 * prompt so an empty reply could not be mistaken for an unformatted one:
 *
 * | | tables | headings | bullets | length |
 * | --- | ---: | ---: | ---: | ---: |
 * | 2.6B, without this | 1 of 4 | 0 of 4 | 2 of 4 | 1,279 chars |
 * | 2.6B, with it | **3 of 4** | **4 of 4** | **4 of 4** | **1,698** |
 * | 1.2B, without this | 0 of 4 | 0 of 4 | 0 of 4 | 379 |
 * | 1.2B, with it | 0 of 4 | 0 of 4 | 1 of 4 | 490 |
 *
 * Headings from none to every one is worth the thirty five tokens this costs on every turn.
 * Worded as permission rather than instruction: "use headings and tables" gets headings on a
 * one sentence answer, which is worse than plain text.
 */
/**
 * What a goal's first turn asks for.
 *
 * A plan and nothing else. Plan mode already refuses to run tools and asks when the request
 * is ambiguous; this only says what shape the answer should take, because the steps are
 * parsed back out of it and a paragraph is not a list.
 */
/**
 * The three sentences that turn the same loop into a different kind of work.
 *
 * A goal carries out actions and reports on each; research answers questions and then writes
 * up what it found. Everything else about running unattended on a phone is identical, which
 * is why this is a parameter rather than a second loop.
 */
private data class Brief(
    /** What the foreground notification says while this is running. */
    val notification: String,
    /** Asked once, and parsed back into a plan. */
    val plan: String,
    /** Asked for every step, pointed at that step alone. */
    val step: String,
    /** Asked after the last step, or null for work whose answer is the last step. */
    val finish: String? = null,
    /** Execution mode for each step, or null to preserve the user's chosen mode. */
    val executionMode: AgentMode? = null,
    /** Whether each step must prove a correlated web search and page fetch. */
    val requiresWebEvidence: Boolean = false,
    /**
     * Whether the planning turn is offered ask_user at all.
     *
     * True for a goal, where the action is going to touch the user's own files and a
     * genuine ambiguity is worth a question before anything runs. False for research: the
     * prompt alone saying not to ask was not enough, measured against exactly the failure
     * that motivated it, so the tool is not offered rather than merely discouraged.
     */
    val offersAskDuringPlan: Boolean = true,
    /**
     * What to work through when the planning turn did not produce a plan of its own.
     *
     * Null for a goal: guessing a list of actions to carry out on the user's own files,
     * unread by anyone, is not a safe thing to default to. Research's own fallback plans
     * one step, the question exactly as asked — read-only, and the one thing the model
     * was already asked to do, so there is nothing about it to have guessed.
     */
    val fallbackPlan: (String) -> TaskPlan? = { null },
) {
    companion object {
        val GOAL = Brief(
            notification = "Working on a goal",
            plan = GOAL_PLAN_PREFIX,
            step = GOAL_STEP_PREFIX,
        )

        /**
         * Researching, which is planning questions rather than actions.
         *
         * "Do not answer them yet" is doing real work in the first sentence. Asked to break
         * a question down, a small model answers the whole thing in the planning turn and
         * then has nothing left to research, which is the same failure the goal planner has
         * and needs the same explicit instruction.
         *
         * The second sentence exists because plan mode's only other tool is ask_user, and
         * not recognising the subject of a research request reads to a small model as
         * exactly the kind of thing to ask about — asked to research a name it does not
         * know, it asked the person who is Alpha Romer rather than putting that question on
         * the list, which is the one place in this whole loop research can actually search
         * for an answer. Not knowing the subject is not an ambiguity to resolve before
         * planning; it is usually the plan's first question.
         *
         * The step prompt names the address because a report with no sources is the one
         * output here nobody can check, and a model that is not told to keep the link does
         * not keep it.
         */
        val RESEARCH = Brief(
            notification = "Researching",
            plan = "Break this into a short numbered list of specific questions, five at " +
                "most, that could each be answered by searching the web. Do not answer " +
                "any of them yet. If you do not already know who or what this is about, " +
                "that is not a reason to ask first: searching to find out is the research " +
                "itself, so make it one of the questions instead.",
            step = "Research this one question. Search the web, read the best source you " +
                "find, and report what it says along with the address you found it at. " +
                "Only state facts that are actually in what you read; do not add a date, " +
                "a figure, or a name from memory just because it sounds like it belongs. " +
                "Do not research the other questions.",
            offersAskDuringPlan = false,
            finish = "Now write the findings up as one document in Markdown. Start with a " +
                "heading, then the answer in a few short sections. End with a Sources " +
                "section listing the addresses you used. Do not search again: write only " +
                "from what you found. If something could not be answered, say so rather " +
                "than filling the gap.",
            executionMode = AgentMode.AUTO,
            requiresWebEvidence = true,
            fallbackPlan = { task ->
                TaskPlan(listOf(TaskStep(task.take(TaskPlan.MAX_STEP_CHARS))))
            },
        )
    }
}

private const val GOAL_PLAN_PREFIX: String =
    "Plan this out as a short numbered list of steps, five at most, each one a single " +
        "action you could carry out on this phone. Do not do any of them yet."

/** PLAN produces a goal's plan; it is never a usable mode for carrying that plan out. */
internal fun goalExecutionMode(requested: AgentMode?, previous: AgentMode): AgentMode =
    requested ?: previous.takeUnless { it == AgentMode.PLAN } ?: AgentMode.AUTO

/**
 * What every later turn asks for.
 *
 * Pointed at one step rather than the whole goal, because a small model handed the whole
 * list re-plans instead of working: measured elsewhere in this repository, the model is at
 * its best when the next action is the only thing in front of it.
 */
private const val GOAL_STEP_PREFIX: String =
    "Carry out this one step of the plan and report what happened. Do not do the other " +
        "steps."

/**
 * Replaces the configured tool prompt for a research step. See [ChatViewModel.step].
 *
 * The configured default opens with "you already know the answer to most questions", which
 * is the right bias for ordinary chat and the wrong one here: a research step only exists
 * because the plan already decided the answer was not already known, and telling the model
 * to lean on memory anyway is what let a step answer "I don't have the current information
 * stored" instead of searching for it.
 */
private const val RESEARCH_STEP_TOOL_PROMPT: String =
    "This step exists to search, not to answer from memory: the plan already decided this " +
        "question needed one. Search the web for it and read a real source before " +
        "answering. If the first search does not settle it, change the query and search " +
        "again rather than answering from what you already believe or saying you cannot " +
        "know — one weak search is not evidence the answer is unavailable, only that the " +
        "first query was."

/**
 * Where a goal stops rather than flatten the phone.
 *
 * Fifteen percent, which is the band Android itself starts warning in. Working on its own is
 * the most expensive thing this app does: sustained decode holds several cores at full clock
 * for minutes, and a goal left running is the one case where nobody is watching the battery
 * because nobody is watching the screen.
 */
private const val MIN_BATTERY_PERCENT = 15

/**
 * Consecutive failed steps before a goal gives up.
 *
 * Two, so the common case of a tool call the model can repair once it reads the error gets
 * its retry, and a goal that is simply stuck stops rather than working through the battery.
 */
private const val MAX_STEP_FAILURES = 2

/** What one step of a goal turned out to mean for the loop running it. */
private enum class StepOutcome {
    /** It finished, and the plan moved on. */
    DONE,

    /** It failed in a way worth trying once more. */
    RETRY,

    /** The goal is over, whether finished, halted, or cancelled. */
    STOP,
}

private const val MARKDOWN_STYLE: String =
    "Use Markdown when it makes the answer easier to read: headings for sections, bullets " +
        "for lists, and a table when you are comparing things across the same few fields."

/**
 * What actually gets sent to the model: the compaction summary, if any, followed by the
 * turns that were not folded into it.
 *
 * @param toolPromptOverride Replaces [ModelPreferences.toolPrompt] for this one turn. The
 *   configured prompt is tuned for ordinary chat, where reaching for a tool the model
 *   probably does not need is the failure worth avoiding; a research step is the opposite
 *   case; see the override research steps pass.
 */
internal fun ChatUiState.engineMessages(toolPromptOverride: String? = null): List<ChatMessage> {
    val instructions = listOfNotNull(
        // A model cannot tell that "this year's final" is past its training data if nobody
        // tells it what year it is, and it will answer from memory rather than look. Eight
        // tokens, and on the routing set it was most of the difference between eleven right
        // out of twenty four and eighteen.
        "Today is ${LocalDate.now()}.",
        AnswerLength.fromName(preferences.answerLength).instruction,
        MARKDOWN_STYLE,
        preferences.systemPrompt.takeIf { it.isNotBlank() },
        // After the user's own instructions and before anything about tools, because it is
        // background rather than an order. It is also the same tokens on every turn, so it
        // sits in the stable part of the prompt where the KV cache keeps it and it is paid
        // for once rather than per question. See Memory.
        memories,
        // Plan mode says its piece whether or not any tool is switched on, because it is a
        // mode the user chose and it changes how the model is meant to answer. Gated on
        // tools being available, "/plan" with everything switched off sent no instruction
        // at all and the mode was a silent no-op.
        toolInstruction(
            mode,
            toolPromptOverride ?: preferences.toolPrompt,
            anyTools = toolsAvailable,
        ),
    ).joinToString("\n\n")

    val system = instructions
        .takeIf { it.isNotBlank() }
        ?.let { listOf(ChatMessage.text(ChatRole.SYSTEM, it)) }
        .orEmpty()

    val remaining = compaction
        ?.let { transcript.drop(it.foldedThroughIndex + 1) }
        ?: transcript

    return (system + recap(compaction) + remaining.map { it.toChatMessage() })
        .asExchange().withToolNotes(toolNotes)
}

/**
 * The folded turns, handed back as a turn rather than as an instruction.
 *
 * The summary used to be appended to the system message, and the reason given was that a
 * second system message beside the first made Gemma 3's template raise: it enforces strict
 * user-then-assistant alternation. That reason is real and it argues against a *system*
 * turn, not against a turn. An ordinary exchange alternates correctly and renders on every
 * template here.
 *
 * The position turned out to matter a great deal more than the wording. Given a summary that
 * contained the answer, and the eight shipped tools, the model was asked seven questions the
 * summary answers:
 *
 * | summary in | answered from it | reached for a tool anyway |
 * | --- | ---: | ---: |
 * | the system message | 4 of 7 | **7 of 7** |
 * | a turn, as here | 6 of 7 | **3 of 7** |
 * | prefixed to the question | 1 of 3 | 2 of 3 |
 *
 * The tool column is the honest one, because a search whose query quotes the fact counts as
 * "answered" on a string match and is not an answer. Seven of seven means that after a fold
 * the model looked for what it had just been told, every time, and on a phone with a shared
 * folder that search comes back empty.
 *
 * The reply is put in the model's own mouth, which is a prompt-construction device and not
 * something it said. That is the same liberty [withToolNotes] takes, and it is what makes the
 * difference: a question with the summary merely prefixed to it scored no better than the
 * system message. What works is the conversation having already acknowledged it.
 */
private fun recap(compaction: Compaction?): List<ChatMessage> {
    val summary = compaction?.summary?.takeIf { it.isNotBlank() } ?: return emptyList()
    return listOf(
        ChatMessage.text(ChatRole.USER, "Earlier in this conversation:\n$summary"),
        ChatMessage.text(ChatRole.ASSISTANT, "Understood, I have that."),
    )
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
    // First, because it is the one that used to fall through to null: a caller refused for
    // this reason was told nothing at all, and the goal loop reported its own halt as "the
    // turn could not be started" with no idea why.
    isGenerating -> "A reply is still being written. Wait for it, or stop it first."
    isLoadingModel -> "The model is still loading. Ask again in a moment."
    modelName == null -> "No model is loaded yet. Choose one in Models."
    outputModality == OutputModality.SPEECH ->
        "This model generates speech, which this build can detect but cannot play yet. " +
            "Choose a text model in Models."
    // Reached only if the composer let a tap through anyway. The Send button is disabled
    // for the same condition, so this is the belt to that pair of braces.
    isCompacting -> "Making room by summarising earlier turns. This takes a few seconds."
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
            totalMillis = message.totalMillis,
            promptTokens = message.promptTokens,
            cachedTokens = message.cachedTokens,
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
 * This conversation's running input/output token counts and cache hit rate, for the status
 * line above the composer.
 *
 * Summed from the transcript itself rather than kept as separate running counters: every
 * place this conversation's transcript is cleared or replaced — a new chat, switching
 * conversations, an edit that drops later turns, reopening one from storage — already has to
 * get that right for the messages themselves, and a sum over what is actually on screen can
 * never drift from it. A separate counter would need its own reset at every one of those
 * sites and would eventually miss one.
 */
internal fun ChatUiState.sessionTokens(): SessionTokens? {
    val measured = transcript.filter { it.role == ChatRole.ASSISTANT && it.promptTokens != null }
    if (measured.isEmpty()) return null
    val input = measured.sumOf { it.promptTokens ?: 0 }
    val output = measured.sumOf { it.generatedTokens ?: 0 }
    val cached = measured.sumOf { it.cachedTokens ?: 0 }
    return SessionTokens(
        inputTokens = input,
        outputTokens = output,
        // input is 0 only in the unreachable case of every measured turn being a full cache
        // hit on an empty prompt; guarded rather than assumed away.
        cacheHitRate = if (input > 0) cached.toDouble() / input else 0.0,
    )
}

/** See [ChatUiState.sessionTokens]. */
internal data class SessionTokens(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheHitRate: Double,
)

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
internal fun toolInstruction(mode: AgentMode, configured: String, anyTools: Boolean): String? =
    when (mode) {
        // Two wordings, because the first one is a lie when nothing is switched on, and a
        // model told it has tools it does not have writes a plan around using them.
        // No longer says "do not call them", because there is now nothing in the prompt to
        // call: plan mode is handed the machinery and nothing else. See TurnRunner.
        AgentMode.PLAN -> if (anyTools) {
            "Do not act on anything yet. Say what you would do and why, as short steps. If " +
                "the request could mean more than one thing, or a detail you would need was " +
                "never given, ask before planning."
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

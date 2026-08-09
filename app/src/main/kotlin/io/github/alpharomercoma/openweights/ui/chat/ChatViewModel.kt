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
import io.github.alpharomercoma.openweights.core.data.ChatRepository
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.data.ModelPreferencesRepository
import io.github.alpharomercoma.openweights.core.data.decodeAttachments
import io.github.alpharomercoma.openweights.core.device.ThermalPolicy
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.MediaSupport
import io.github.alpharomercoma.openweights.core.engine.StopReason
import io.github.alpharomercoma.openweights.model.AttachmentStore
import io.github.alpharomercoma.openweights.model.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
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
    /** Tools the model asked for. Shown as steps; execution is not wired up yet. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** Set on the first entry that survives a compaction, so the fold is visible. */
    val compactionNote: String? = null,
    /** Files sent with this turn, shown above its text. */
    val attachments: List<MessagePart.File> = emptyList(),
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

    /** True while the runtime is busy, which is when the state is worth showing at all. */
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
    /** Attachments staged in the composer, not yet sent. */
    val staged: List<MessagePart.File> = emptyList(),
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
    private val engine: InferenceEngine,
    private val compactor: ConversationCompactor,
    private val modelStore: ModelStore,
    private val attachments: AttachmentStore,
    private val chats: ChatRepository,
    private val modelPreferences: ModelPreferencesRepository,
    private val thermalPolicy: ThermalPolicy,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private var nextEntryId = 0L

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
        }

    init {
        viewModelScope.launch {
            chats.observeConversations().collect { rows ->
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

    /** Loads whichever model is already on disk, if any. */
    fun loadDefaultModel() {
        modelStore.firstAvailableModel()?.let(::loadModel)
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
        viewModelScope.launch {
            generationJob?.join()
            _uiState.update { it.copy(isLoadingModel = true, error = null) }
            val preferences = modelPreferences.current(modelFile.name)
            val loadParams = contextLength
                ?.let { ModelLoadParams(contextLength = it) }
                ?: preferences.toLoadParams()

            val projector = modelStore.projectorFor(modelFile)

            // Cleared before the attempt: the engine frees whatever it held before it loads,
            // so from here on the old identity describes nothing.
            _uiState.update {
                it.copy(
                    modelName = null,
                    modelQuantization = null,
                    backend = null,
                    contextSize = 0,
                    contextUsed = 0,
                    mediaSupport = MediaSupport(),
                )
            }

            runCatching {
                engine.load(modelFile, loadParams, projector)
            }.onSuccess {
                // The cache belonged to the old weights. Clearing it makes the next reply
                // re-read the transcript, which is what carries the conversation across.
                engine.resetContext()
                if (!keepConversation) conversationId = null
                preferencesKey = modelFile.name
                val info = engine.loadedModel
                _uiState.update {
                    it.copy(
                        isLoadingModel = false,
                        backend = engine.computeDevices().firstOrNull()?.id?.uppercase(),
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
                        mediaSupport = info?.mediaSupport ?: MediaSupport(),
                        supportsThinking = info?.supportsThinking == true,
                        // Dropped either way: an attachment staged for a model that could
                        // read it may be one the new model cannot.
                        staged = emptyList(),
                    )
                }
                if (keepConversation) {
                    conversationId?.let { id -> chats.setModel(id, modelFile.nameWithoutExtension) }
                }
            }.onFailure { failure ->
                _uiState.update { it.copy(isLoadingModel = false, error = failure.userMessage()) }
            }
        }
    }

    fun send(prompt: String) {
        val text = prompt.trim()
        val staged = _uiState.value.staged
        // An attachment on its own is a complete message: "what is this?" is implied.
        if ((text.isEmpty() && staged.isEmpty()) || !_uiState.value.canSend) return

        // isGenerating is claimed here, before any suspending work: two quick taps would
        // otherwise both pass canSend, create two conversations, and race the engine.
        _uiState.update { state ->
            state.copy(
                transcript = state.transcript +
                    entry(ChatRole.USER, text).copy(attachments = staged),
                isGenerating = true,
                staged = emptyList(),
                error = null,
            )
        }

        // Awaited before generating: a fast reply could otherwise finish before the row
        // exists and be dropped, taking its usage record with it.
        viewModelScope.launch {
            val title = text.ifEmpty { staged.firstOrNull()?.describe() ?: "Attachment" }
            val id = conversationId
                ?: chats.startConversation(title, _uiState.value.modelName)
                    .also { conversationId = it }
            chats.addMessage(id, ChatRole.USER.wireName, text, attachments = staged)
            generate()
        }
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
            val stored = try {
                attachments.store(uri)
            } finally {
                _uiState.update { it.copy(isAttaching = false) }
            }
            _uiState.update { state ->
                state.copy(
                    staged = state.staged + stored,
                    error = if (stored.isEmpty()) "That file could not be read." else state.error,
                )
            }
        }
    }

    /** Removes a staged attachment and deletes the copy that was made of it. */
    fun removeStaged(attachment: MessagePart.File) {
        _uiState.update { it.copy(staged = it.staged - attachment) }
        viewModelScope.launch { attachments.discard(attachment) }
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
            )
        }
        generate()
    }

    private fun generate() {
        val state = _uiState.value
        val conversation = state.engineMessages()
        if (conversation.isEmpty()) return

        _uiState.update { state ->
            state.copy(
                transcript = state.transcript +
                    entry(ChatRole.ASSISTANT, "").copy(isStreaming = true),
                isGenerating = true,
            )
        }

        generationJob = viewModelScope.launch {
            val reply = StringBuilder()
            val startedAt = System.currentTimeMillis()
            var reasoningEndedAt: Long? = null

            try {
                // Re-planned per reply: the phone is a different machine hot than cold,
                // and a count chosen at load time is wrong by the third long answer.
                // Inside the try because it suspends, so Stop can land here too.
                if (!applyThreadPlan()) return@launch

                var lastFrameAt = 0L

                engine.chat(conversation, state.preferences.toSamplerParams()).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            reply.append(event.text)

                            // Not every token: re-parsing the whole reply and rebuilding
                            // its markdown tree costs more than a frame, so publishing per
                            // token on a phone that is also running the model means the
                            // list never settles between updates. Coalescing to roughly
                            // every other frame is still faster than anyone reads.
                            val now = System.currentTimeMillis()
                            if (now - lastFrameAt < STREAM_FRAME_MS) return@collect
                            lastFrameAt = now

                            val parsed = parseAssistantReply(reply.toString())
                            if (reasoningEndedAt == null &&
                                parsed.reasoning != null &&
                                !parsed.isReasoningInProgress
                            ) {
                                reasoningEndedAt = System.currentTimeMillis()
                            }
                            applyStreamedText(reply.toString(), parsed, reasoningEndedAt, startedAt)
                        }

                        is GenerationEvent.Completed -> {
                            // The coalescing above can have skipped the last few tokens,
                            // so the finished reply is applied in full regardless.
                            applyCompletion(event)
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                // Stop was pressed. Keep what was produced and do not report it as an error.
                updateLastEntry { it.copy(isStreaming = false, isReasoningInProgress = false) }
                _uiState.update { it.copy(isGenerating = false) }
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                updateLastEntry { it.copy(isStreaming = false, isReasoningInProgress = false) }
                _uiState.update { it.copy(error = failure.userMessage()) }
            }
            // Re-read rather than leave the last reading standing: a phone that has cooled
            // between replies should stop claiming it is hot.
            _uiState.update {
                it.copy(isGenerating = false, isThrottled = thermalPolicy.isThrottling())
            }
            compactIfNeeded()
        }
    }

    /**
     * Re-plans the thread count for this reply, and says whether to go ahead.
     *
     * Re-planned per reply because the phone is a different machine hot than cold, and a
     * count chosen at load time is wrong by the third long answer. Returns false when the
     * device is hot enough that the right move is to stop rather than to stop slightly
     * less: sustained inference is among the heaviest things this hardware can do.
     */
    private suspend fun applyThreadPlan(): Boolean {
        val plan = thermalPolicy.plan()
        _uiState.update { it.copy(isThrottled = thermalPolicy.isThrottling()) }

        if (plan.shouldPause) {
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
        engine.setThreads(plan.generateThreads, plan.batchThreads)
        return true
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
            compactor.compact(state)
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
                chats.saveCompaction(id, compaction.summary, compaction.foldedThroughIndex)
            }
        }

        _uiState.update { current ->
            if (compaction == null) {
                current
            } else {
                current.copy(
                    compaction = compaction,
                    transcript = current.transcript.mapIndexed { index, entry ->
                        if (index == compaction.foldedThroughIndex + 1) {
                            entry.copy(compactionNote = COMPACTION_NOTE)
                        } else {
                            entry
                        }
                    },
                    contextUsed = 0,
                )
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
            engine.resetContext()
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
     * re-decode anyway, but clearing makes the state honest rather than accidental.
     */
    fun openConversation(id: Long) {
        viewModelScope.launch {
            stop()
            generationJob?.join()
            engine.resetContext()

            val conversation = chats.conversation(id)
            if (conversation == null) {
                // Deleted between the tap and this read; adopting the id would make the
                // next message violate the foreign key.
                newChat()
                return@launch
            }

            val messages = chats.messages(id)
            conversationId = id
            nextEntryId = 0

            // A conversation continued under a different model would mix two models'
            // voices in one transcript, and the history would not say which said what.
            val currentModel = _uiState.value.modelName
            val mismatch = conversation.modelName != null &&
                currentModel != null &&
                conversation.modelName != currentModel

            _uiState.update { state ->
                state.copy(
                    transcript = messages.map { message ->
                        val parsed = parseAssistantReply(message.text)
                        TranscriptEntry(
                            id = nextEntryId++,
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
                        )
                    },
                    compaction = conversation.compactionSummary?.let {
                        Compaction(it, conversation.compactionThroughIndex, 0)
                    },
                    contextUsed = 0,
                    error = if (mismatch) {
                        "This chat was written by ${conversation.modelName}. Replies will " +
                            "now come from $currentModel."
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** Saves settings for the loaded model. Context length applies at the next load. */
    fun savePreferences(preferences: ModelPreferences) {
        val model = preferencesKey ?: return
        viewModelScope.launch {
            modelPreferences.save(model, preferences)
            _uiState.update { it.copy(preferences = preferences) }
        }
    }

    fun resetPreferences() {
        val model = preferencesKey ?: return
        viewModelScope.launch {
            modelPreferences.reset(model)
            _uiState.update { it.copy(preferences = ModelPreferences()) }
        }
    }

    /** Deletes a conversation; if it is the open one, the screen returns to a blank chat. */
    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            // Read before deleting: the rows are what says which files were attached, and
            // once they are gone nothing else on disk remembers, so the photos would stay
            // forever in a folder the user cannot see.
            val orphaned = chats.messages(id).flatMap { it.attachments.decodeAttachments() }
            chats.deleteConversation(id)
            attachments.discard(orphaned)
            if (conversationId == id) newChat()
        }
    }

    /** Stops the running generation, keeping whatever has been produced so far. */
    fun stop() {
        engine.cancel()
        generationJob?.cancel()
    }

    private fun applyCompletion(event: GenerationEvent.Completed) {
        persistReply(event)
        updateLastEntry {
            // The engine has already lifted any tool call out of the text, so the answer
            // shown never contains the raw invocation syntax.
            val parsed = parseAssistantReply(event.content.ifEmpty { it.text })
            it.copy(
                answer = parsed.answer,
                reasoning = parsed.reasoning ?: it.reasoning,
                toolCalls = event.toolCalls,
                isStreaming = false,
                isReasoningInProgress = false,
                tokensPerSecond = event.stats.decodeTokensPerSecond,
                timeToFirstTokenMs = event.stats.timeToFirstTokenMs,
                generatedTokens = event.stats.generatedTokens,
            )
        }
        _uiState.update {
            it.copy(
                contextUsed = event.stats.contextUsed,
                contextSize = event.stats.contextSize,
                error = event.reason.warning(),
            )
        }
    }

    /**
     * Writes the finished reply and folds it into the lifetime ledger.
     *
     * Two writes on purpose: the message carries this reply's own numbers, and the ledger
     * carries the totals, so deleting the chat later does not un-count the work.
     */
    private fun persistReply(event: GenerationEvent.Completed) {
        val id = conversationId ?: return
        val reply = _uiState.value.transcript.lastOrNull() ?: return
        val model = _uiState.value.modelName ?: return

        viewModelScope.launch {
            chats.addMessage(
                conversationId = id,
                role = ChatRole.ASSISTANT.wireName,
                text = reply.text,
                tokensPerSecond = event.stats.decodeTokensPerSecond,
                timeToFirstTokenMs = event.stats.timeToFirstTokenMs,
                generatedTokens = event.stats.generatedTokens,
                reasoningMs = reply.reasoningMs,
            )
            chats.recordUsage(
                modelName = model,
                promptTokens = event.stats.promptTokens,
                generatedTokens = event.stats.generatedTokens,
                inferenceMs = event.stats.prefillMs + event.stats.decodeMs,
            )
        }
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
        engine.cancel()
        super.onCleared()
    }
}

/**
 * How often streamed text reaches the screen, in milliseconds.
 *
 * Two frames at 60 Hz. Below this the work per update: re-parse, recompose, re-measure,
 * re-scroll: starts to overlap itself and the transcript visibly judders; above it the
 * text arrives in visible chunks.
 */
private const val STREAM_FRAME_MS = 33L

private const val COMPACTION_NOTE = "Earlier turns folded into a summary to make room."

/**
 * What actually gets sent to the model: the compaction summary, if any, followed by the
 * turns that were not folded into it.
 */
internal fun ChatUiState.engineMessages(): List<ChatMessage> {
    val system = preferences.systemPrompt
        .takeIf { it.isNotBlank() }
        ?.let { listOf(ChatMessage.text(ChatRole.SYSTEM, it)) }
        .orEmpty()

    val compaction = compaction
        ?: return system + transcript.map { it.toChatMessage() }
    val summary = ChatMessage.text(
        ChatRole.SYSTEM,
        "Summary of the earlier conversation:\n" + compaction.summary,
    )
    val remaining = transcript.drop(compaction.foldedThroughIndex + 1)
    return system + summary + remaining.map { it.toChatMessage() }
}

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

/** A short human label for an attachment, used where there is no text to go on. */
internal fun MessagePart.File.describe(): String = name ?: when (kind) {
    MediaKind.IMAGE -> "Image"
    MediaKind.AUDIO -> "Audio"
    MediaKind.VIDEO -> "Video"
    MediaKind.OTHER -> "File"
}

/** Turns engine failures into something a person can act on. */
private fun Throwable.userMessage(): String =
    message ?: "Generation failed (${this::class.simpleName})."

/** Some stop reasons are worth surfacing; a normal end of turn is not. */
private fun StopReason.warning(): String? = when (this) {
    StopReason.CONTEXT_FULL ->
        "The context window is full. Start a new chat, or raise the context length in " +
            "model settings."

    StopReason.END_OF_TURN, StopReason.MAX_TOKENS, StopReason.CANCELLED, StopReason.ERROR -> null
}

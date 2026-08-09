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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.common.context.Compaction
import io.github.alpharomercoma.openweights.core.common.model.AssistantReply
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.StopReason
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
    /** Set on the first entry that survives a compaction, so the fold is visible. */
    val compactionNote: String? = null,
)

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
) {
    val canSend: Boolean get() = modelName != null && !isGenerating && !isLoadingModel

    /** How full the model's context window is, as a fraction. */
    val contextFraction: Float
        get() = if (contextSize > 0) contextUsed.toFloat() / contextSize else 0f
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engine: InferenceEngine,
    private val compactor: ConversationCompactor,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private var nextEntryId = 0L

    /**
     * True once a model has been loaded or is loading, so the screen can ask for the
     * default model on first composition without reloading it after a rotation.
     */
    val hasModel: Boolean
        get() = _uiState.value.modelName != null || _uiState.value.isLoadingModel

    /** Loads a GGUF file from disk. */
    fun loadModel(modelFile: File, contextLength: Int = ModelLoadParams.DEFAULT_CONTEXT_LENGTH) {
        // A generation in flight writes into the transcript we are about to replace, so it
        // has to be finished before the new model is loaded, not merely asked to stop.
        stop()
        viewModelScope.launch {
            generationJob?.join()
            _uiState.update { it.copy(isLoadingModel = true, error = null) }
            runCatching {
                engine.load(modelFile, ModelLoadParams(contextLength = contextLength))
            }.onSuccess {
                val info = engine.loadedModel
                _uiState.update {
                    it.copy(
                        isLoadingModel = false,
                        modelName = modelFile.nameWithoutExtension,
                        modelQuantization = info?.description,
                        contextSize = info?.contextSize ?: 0,
                        contextUsed = info?.contextUsed ?: 0,
                        transcript = emptyList(),
                    )
                }
            }.onFailure { failure ->
                _uiState.update { it.copy(isLoadingModel = false, error = failure.userMessage()) }
            }
        }
    }

    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || !_uiState.value.canSend) return

        _uiState.update { state ->
            state.copy(transcript = state.transcript + entry(ChatRole.USER, text), error = null)
        }
        generate()
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
        val conversation = _uiState.value.engineMessages()
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
                engine.chat(conversation, SamplerParams()).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            reply.append(event.text)
                            val parsed = parseAssistantReply(reply.toString())
                            if (reasoningEndedAt == null &&
                                parsed.reasoning != null &&
                                !parsed.isReasoningInProgress
                            ) {
                                reasoningEndedAt = System.currentTimeMillis()
                            }
                            applyStreamedText(reply.toString(), parsed, reasoningEndedAt, startedAt)
                        }

                        is GenerationEvent.Completed -> applyCompletion(event)
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
            _uiState.update { it.copy(isGenerating = false) }
            compactIfNeeded()
        }
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

        _uiState.update { it.copy(isCompacting = true) }
        val compaction = compactor.compact(state)

        _uiState.update { current ->
            if (compaction == null) {
                current.copy(isCompacting = false)
            } else {
                current.copy(
                    isCompacting = false,
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
        viewModelScope.launch {
            stop()
            engine.resetContext()
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

    /** Stops the running generation, keeping whatever has been produced so far. */
    fun stop() {
        engine.cancel()
        generationJob?.cancel()
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private fun applyCompletion(event: GenerationEvent.Completed) {
        updateLastEntry {
            it.copy(
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

private const val COMPACTION_NOTE = "Earlier turns folded into a summary to make room."

/**
 * What actually gets sent to the model: the compaction summary, if any, followed by the
 * turns that were not folded into it.
 */
internal fun ChatUiState.engineMessages(): List<ChatMessage> {
    val compaction = compaction ?: return transcript.map { ChatMessage.text(it.role, it.text) }
    val summary = ChatMessage.text(
        ChatRole.SYSTEM,
        "Summary of the earlier conversation:\n" + compaction.summary,
    )
    val remaining = transcript.drop(compaction.foldedThroughIndex + 1)
    return listOf(summary) + remaining.map { ChatMessage.text(it.role, it.text) }
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

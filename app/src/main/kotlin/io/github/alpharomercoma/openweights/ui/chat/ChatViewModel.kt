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
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.StopReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** A turn as shown in the transcript, with the measurements taken while producing it. */
data class TranscriptEntry(
    val role: ChatRole,
    val text: String,
    val tokensPerSecond: Double? = null,
    val timeToFirstTokenMs: Long? = null,
    val generatedTokens: Int? = null,
    val isStreaming: Boolean = false,
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
) {
    val canSend: Boolean get() = modelName != null && !isGenerating && !isLoadingModel
}

@HiltViewModel
class ChatViewModel
@Inject
constructor(private val engine: InferenceEngine) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null

    /** Loads a GGUF file from disk. Phase 1 sources these from a developer-visible folder. */
    fun loadModel(modelFile: File, contextLength: Int = ModelLoadParams.DEFAULT_CONTEXT_LENGTH) {
        viewModelScope.launch {
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
                _uiState.update {
                    it.copy(isLoadingModel = false, error = failure.userMessage())
                }
            }
        }
    }

    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || !_uiState.value.canSend) return

        _uiState.update {
            it.copy(
                transcript = it.transcript +
                    TranscriptEntry(ChatRole.USER, text) +
                    TranscriptEntry(ChatRole.ASSISTANT, "", isStreaming = true),
                isGenerating = true,
                error = null,
            )
        }

        val conversation = _uiState.value.transcript
            .dropLast(1)
            .map { ChatMessage.text(it.role, it.text) }

        generationJob = viewModelScope.launch {
            val reply = StringBuilder()
            runCatching {
                engine.chat(conversation, SamplerParams()).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            reply.append(event.text)
                            updateLastEntry { it.copy(text = reply.toString()) }
                        }

                        is GenerationEvent.Completed -> {
                            updateLastEntry {
                                it.copy(
                                    text = reply.toString(),
                                    isStreaming = false,
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
                    }
                }
            }.onFailure { failure ->
                updateLastEntry { it.copy(text = reply.toString(), isStreaming = false) }
                _uiState.update { it.copy(error = failure.userMessage()) }
            }
            _uiState.update { it.copy(isGenerating = false) }
        }
    }

    /** Stops the running generation, keeping whatever has been produced so far. */
    fun stop() {
        engine.cancel()
        generationJob?.cancel()
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

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

/** Turns engine failures into something a person can act on. */
private fun Throwable.userMessage(): String =
    message ?: "Generation failed (${this::class.simpleName})."

/** Some stop reasons are worth surfacing; a normal end of turn is not. */
private fun StopReason.warning(): String? = when (this) {
    StopReason.CONTEXT_FULL ->
        "The context window is full. Start a new chat, or raise the context length in model settings."
    StopReason.END_OF_TURN, StopReason.MAX_TOKENS, StopReason.CANCELLED, StopReason.ERROR -> null
}

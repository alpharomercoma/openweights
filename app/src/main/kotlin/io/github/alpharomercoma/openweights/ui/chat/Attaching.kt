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
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Staging what goes with the message being typed.
 *
 * Its own object because it is its own job. [ChatViewModel] owns the engine's lifetime, the
 * transcript, storage, folding and the conversation drawer, and detekt has now said it is too
 * large for the third time; each of the previous two was paid with a real extraction rather
 * than a raised threshold, and this is the third.
 *
 * What makes it a clean seam is that nothing here needs the turn loop or the database. A file
 * is copied in, or refused, and the composer shows the result. The one thing it shares with
 * the rest is the window: how much of a document fits depends on how much context is left,
 * which is why the budget is computed per attachment rather than once.
 */
internal class Attaching(
    private val staging: Staging,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
    private val limitMessage: String,
    private val unreadableMessage: String,
    private val loadingMessage: String,
) {
    private val attachMutex = Mutex()

    /**
     * Copies a picked file in and stages it for the next message.
     *
     * Copied in immediately rather than at send time, so the thumbnail appears at once and
     * the picker's read permission is used while it is still granted.
     */
    fun attach(uri: Uri) {
        scope.launch {
            attachMutex.withLock {
                if (state.value.staged.size >= MAX_STAGED_ATTACHMENTS) {
                    state.update { it.copy(error = limitMessage) }
                    return@withLock
                }
                state.update { it.copy(isAttaching = true) }
                // In a finally: a throw here would otherwise leave the attach button spinning
                // with no way back to it.
                try {
                    accept(safelyStage(uri))
                } finally {
                    state.update { it.copy(isAttaching = false) }
                }
            }
        }
    }

    /**
     * Stages several files at once, in the order they were chosen.
     *
     * Sequentially rather than concurrently, and the reason is the shared folder rather
     * than politeness: each one is copied into the app's own storage, and eight copies
     * racing on a phone's flash is slower than eight in a row as well as harder to report
     * on. The spinner is raised once for the whole batch, so it does not flicker per file.
     *
     * A file that cannot be read stops nothing. Refusals are collected and reported once at
     * the end, because "three of these five are not readable by this model" is one useful
     * sentence and five separate errors are noise.
     */
    fun attachAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            attach(uris.single())
            return
        }
        scope.launch {
            attachMutex.withLock {
                if (state.value.staged.size >= MAX_STAGED_ATTACHMENTS) {
                    state.update { it.copy(error = limitMessage) }
                    return@withLock
                }
                state.update { it.copy(isAttaching = true) }
                val refusals = mutableListOf<String>()
                var hitLimit = false
                try {
                    // Every one of them, stopping when the slots are full rather than after
                    // looking at as many files as there were slots when the batch began. A
                    // file that is refused takes no slot, so truncating the input first
                    // meant a batch whose first picture was unreadable dropped its last
                    // picture too, and said the limit had been reached with a slot free.
                    uris.forEach { uri ->
                        if (state.value.staged.size >= MAX_STAGED_ATTACHMENTS) {
                            hitLimit = true
                            return@forEach
                        }
                        when (val staged = safelyStage(uri)) {
                            is Staged.Refused -> refusals += staged.why
                            is Staged.Files -> {
                                if (staged.files.size >
                                    MAX_STAGED_ATTACHMENTS - state.value.staged.size
                                ) {
                                    hitLimit = true
                                }
                                accept(staged)
                            }
                            else -> accept(staged)
                        }
                    }
                } finally {
                    state.update { it.copy(isAttaching = false) }
                }
                if (refusals.isNotEmpty() || hitLimit) {
                    val messages = refusals.distinct().toMutableList()
                    if (hitLimit) messages += limitMessage
                    state.update { it.copy(error = messages.distinct().joinToString(" ")) }
                }
            }
        }
    }

    /** Removes a staged attachment and deletes the copy that was made of it. */
    fun remove(attachment: MessagePart.File) {
        state.update { it.copy(staged = it.staged - attachment) }
        scope.launch { staging.discard(attachment) }
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
            state.update { it.copy(stagedDocument = null) }
            return
        }
        scope.launch {
            state.update { it.copy(isAttaching = true) }
            val staged = try {
                staging.document(uri, documentBudget(state.value))
            } finally {
                state.update { it.copy(isAttaching = false) }
            }
            state.update { it.after(staged) }
        }
    }

    /**
     * Deletes the copies made of files that will never be sent.
     *
     * Taken as a list rather than read off the state, so a caller clearing several things at
     * once can do it in one update and nothing observes a half-cleared screen. They are copies
     * this app made, and a copy nobody will send is a copy nobody will ever delete.
     */
    suspend fun discard(files: List<MessagePart.File>) {
        if (files.isNotEmpty()) staging.discard(files)
    }

    /** Applies the total part budget after expansion, because one video becomes four files. */
    private suspend fun accept(staged: Staged) {
        if (staged !is Staged.Files) {
            state.update { it.after(staged) }
            return
        }
        val remaining = (MAX_STAGED_ATTACHMENTS - state.value.staged.size).coerceAtLeast(0)
        val accepted = staged.files.take(remaining)
        val overflow = staged.files.drop(remaining)
        if (overflow.isNotEmpty()) staging.discard(overflow)
        state.update {
            it.after(Staged.Files(accepted)).copy(
                error = limitMessage.takeIf { overflow.isNotEmpty() } ?: it.error,
            )
        }
    }

    /** A revoked clipboard/provider URI is a refusal, not a failed composer coroutine. */
    private suspend fun safelyStage(uri: Uri): Staged = try {
        // What a model can read is not known until it is loaded, and until then the answer
        // reads as "none". The composer is deliberately live while the weights come into
        // memory, so a picture pasted in that window used to be copied in and then refused
        // with the sentence for a text-only model — about a model that may have eyes.
        if (state.value.isLoadingModel) {
            Staged.Refused(loadingMessage)
        } else {
            settled(staging.file(uri, state.value.mediaSupport))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        android.util.Log.w("Attaching", "Provider refused attachment", failure)
        Staged.Refused(unreadableMessage)
    }

    /**
     * The same question, asked again on the far side of the copy.
     *
     * Copying a file suspends for as long as the file is large, and a model can be switched
     * while it does. The check that let this through went in before those bytes moved: a
     * picture staged against a model with eyes, landing in a composer now pointed at a
     * text-only one, would sit there looking sendable and be dropped by the engine.
     */
    private suspend fun settled(staged: Staged): Staged {
        if (staged !is Staged.Files) return staged
        val why = if (state.value.isLoadingModel) {
            loadingMessage
        } else {
            staging.unreadable(staged.files, state.value.mediaSupport)
        } ?: return staged
        staging.discard(staged.files)
        return Staged.Refused(why)
    }
}

internal const val MAX_STAGED_ATTACHMENTS = 8

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

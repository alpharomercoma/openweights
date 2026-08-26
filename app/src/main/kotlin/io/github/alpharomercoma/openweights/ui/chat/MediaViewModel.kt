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
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.model.AttachmentStore
import io.github.alpharomercoma.openweights.model.Dictation
import io.github.alpharomercoma.openweights.model.DictationState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The device's own media capabilities, as used from the chat screen.
 *
 * Separate from [ChatViewModel] because none of it is about producing a reply: it hands
 * the camera somewhere to write a photo. Both outlive no conversation and neither touches
 * the model, and folding them in was turning the chat view model into the place every
 * unrelated capability landed.
 */
@HiltViewModel
class MediaViewModel @Inject constructor(
    private val dictation: Dictation,
    private val attachments: AttachmentStore,
) : ViewModel() {

    /** A private file for the camera app to write a photo into. */
    fun newCaptureUri(): Uri = attachments.newCaptureUri()

    /** What dictation is hearing, if anything. */
    val dictationState: StateFlow<DictationState> = dictation.state

    /** True when this device can transcribe without a network. */
    val canDictate: Boolean get() = dictation.isAvailable

    /** Starts or stops dictation. [onFinal] receives the finished transcript. */
    fun toggleDictation(onFinal: (String) -> Unit) {
        if (dictation.state.value.isListening) dictation.stop() else dictation.start(onFinal)
    }

    override fun onCleared() {
        dictation.stop()
    }
}

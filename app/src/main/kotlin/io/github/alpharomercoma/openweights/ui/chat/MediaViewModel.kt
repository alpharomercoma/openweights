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
import io.github.alpharomercoma.openweights.model.SpeechReader
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The device's own media capabilities, as used from the chat screen.
 *
 * Separate from [ChatViewModel] because none of it is about producing a reply: it hands
 * the camera somewhere to write a photo and asks the synthesiser to read an answer out.
 * Both outlive no conversation and neither touches the model, and folding them in was
 * turning the chat view model into the place every unrelated capability landed.
 */
@HiltViewModel
class MediaViewModel @Inject constructor(
    private val speech: SpeechReader,
    private val attachments: AttachmentStore,
) : ViewModel() {

    /** A private file for the camera app to write a photo into. */
    fun newCaptureUri(): Uri = attachments.newCaptureUri()

    val isSpeaking: StateFlow<Boolean> = speech.isSpeaking

    /**
     * Starts reading [text], or stops if a reply is already being read.
     *
     * One entry point rather than a start and a stop, because whether it is speaking is
     * state this owns and a caller would only be mirroring it.
     */
    fun toggleReadAloud(text: String) {
        if (isSpeaking.value) speech.stop() else speech.speak(text)
    }

    override fun onCleared() {
        speech.release()
        super.onCleared()
    }
}

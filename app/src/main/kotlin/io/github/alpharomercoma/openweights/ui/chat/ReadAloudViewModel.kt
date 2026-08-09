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
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.model.SpeechReader
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Reading replies aloud.
 *
 * Separate from [ChatViewModel] because it shares nothing with generating them: it holds a
 * synthesiser rather than a model, its state outlives no conversation, and folding it in
 * would have made the chat view model the place where every unrelated capability landed.
 */
@HiltViewModel
class ReadAloudViewModel @Inject constructor(private val speech: SpeechReader) : ViewModel() {

    val isSpeaking: StateFlow<Boolean> = speech.isSpeaking

    /**
     * Starts reading [text], or stops if a reply is already being read.
     *
     * One entry point rather than a start and a stop, because whether it is speaking is
     * state this owns and a caller would only be mirroring it.
     */
    fun toggle(text: String) {
        if (isSpeaking.value) speech.stop() else speech.speak(text)
    }

    override fun onCleared() {
        speech.release()
        super.onCleared()
    }
}

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

package io.github.alpharomercoma.openweights.model

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What dictation is doing, and what it has heard so far. */
data class DictationState(
    val isListening: Boolean = false,
    /** Words heard so far, updated as they are recognised. */
    val partial: String = "",
    val error: String? = null,
)

/**
 * Speech to text, on this device only.
 *
 * Android's default recogniser streams audio to Google. This app's entire promise is that
 * nothing leaves the phone, so it uses the on-device recogniser and nothing else — if the
 * offline language pack is missing, dictation says so rather than quietly opening a network
 * connection the user was told would never happen.
 *
 * That is a real cost: on-device recognition is unavailable on some devices and its models
 * are downloaded lazily by the system. It is still the right call, because the alternative
 * makes the app's central claim untrue for one button.
 */
@Singleton
class Dictation @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val _state = MutableStateFlow(DictationState())
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /**
     * True when this device can transcribe without a network.
     *
     * The API to ask only exists from Android 13. Below that the on-device recogniser can
     * be constructed but not interrogated, so the honest answer is to try.
     */
    val isAvailable: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            true
        }

    /**
     * Starts listening. Must be called on the main thread — [SpeechRecognizer] requires it.
     *
     * @param onFinal called once with the finished transcript.
     */
    fun start(onFinal: (String) -> Unit) {
        if (_state.value.isListening) return
        if (!isAvailable) {
            _state.value = DictationState(
                error = "This device has no offline speech recognition. " +
                    "OpenWeights will not use the online kind.",
            )
            return
        }

        stop()
        val speech = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = speech
        speech.setRecognitionListener(listener(onFinal))
        _state.value = DictationState(isListening = true)

        speech.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            },
        )
    }

    /** Stops listening and releases the recogniser. Safe to call when idle. */
    fun stop() {
        recognizer?.apply {
            stopListening()
            destroy()
        }
        recognizer = null
        if (_state.value.isListening) {
            _state.value = _state.value.copy(isListening = false)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun listener(onFinal: (String) -> Unit) = object : RecognitionListener {
        override fun onPartialResults(results: Bundle?) {
            results.firstTranscript()?.let { heard ->
                _state.value = _state.value.copy(partial = heard)
            }
        }

        override fun onResults(results: Bundle?) {
            val heard = results.firstTranscript().orEmpty()
            _state.value = DictationState()
            if (heard.isNotBlank()) onFinal(heard)
            stop()
        }

        override fun onError(error: Int) {
            _state.value = DictationState(error = readableError(error))
            stop()
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle?.firstTranscript(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    /**
     * The recogniser's error codes as something worth reading.
     *
     * "No match" and the timeouts are not failures — they are what happens when someone
     * taps the mic and then says nothing — so they are worded as the non-events they are.
     */
    private fun readableError(code: Int): String? = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> null

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Dictation needs permission to use the microphone."

        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        -> "No offline language pack for this language yet. " +
            "Android downloads them from the system speech settings."

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy. Try again."
        else -> "Dictation stopped unexpectedly."
    }
}

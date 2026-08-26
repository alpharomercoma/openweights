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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks replies aloud.
 *
 * Open-weight models that generate speech directly are far too large for a phone, so
 * spoken output comes from Android's own synthesiser instead. That keeps the promise the
 * rest of the app makes: the text was produced on this device, and so is the voice reading
 * it. Nothing is sent anywhere to be spoken.
 */
@Singleton
class SpeechReader @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val _isSpeaking = MutableStateFlow(false)

    /** True while a reply is being read. Drives the stop affordance in the UI. */
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var engine: TextToSpeech? = null

    /** Set once the engine reports it is usable, so a failure is silent rather than fatal. */
    private var isReady = false

    /** Queued while the engine starts up, since the first tap usually arrives before it does. */
    private var pending: String? = null

    fun speak(text: String) {
        // Truncated to what the engine accepts: past its limit `speak` returns ERROR and
        // never calls back, which would leave the UI showing "Stop reading" forever.
        val spoken = text.forSpeech().take(TextToSpeech.getMaxSpeechInputLength())
        if (spoken.isBlank()) return

        val current = engine
        if (current == null) {
            pending = spoken
            start()
            return
        }
        if (!isReady) {
            pending = spoken
            return
        }
        _isSpeaking.value = true
        val queued = current.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        // No utterance means no progress callback, so nothing else would ever clear this.
        if (queued != TextToSpeech.SUCCESS) _isSpeaking.value = false
    }

    fun stop() {
        pending = null
        engine?.stop()
        _isSpeaking.value = false
    }

    /** Releases the synthesiser. The app calls this when it is being torn down. */
    fun release() {
        stop()
        engine?.shutdown()
        engine = null
        isReady = false
    }

    private fun start() {
        engine = TextToSpeech(context) { status ->
            isReady = status == TextToSpeech.SUCCESS
            if (!isReady) {
                pending = null
                _isSpeaking.value = false
                return@TextToSpeech
            }
            engine?.language = Locale.getDefault()
            engine?.setOnUtteranceProgressListener(listener)
            pending?.let { queued ->
                pending = null
                speak(queued)
            }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _isSpeaking.value = true
        }

        override fun onDone(utteranceId: String?) {
            _isSpeaking.value = false
        }

        @Deprecated("Required by the framework; the newer overload delegates to it.")
        override fun onError(utteranceId: String?) {
            _isSpeaking.value = false
        }
    }

    private companion object {
        const val UTTERANCE_ID = "openweights-reply"
    }
}

/**
 * A reply as it should be heard rather than read.
 *
 * Code blocks, link targets and heading marks are visual furniture: read aloud verbatim
 * they turn a short answer into a minute of punctuation. Dropping them is the difference
 * between a usable read-aloud and a novelty.
 */
internal fun String.forSpeech(): String = this
    .replace(FENCED_CODE, " (code sample) ")
    .replace(INLINE_CODE, "$1")
    .replace(LINK, "$1")
    .replace(EMPHASIS, "$1")
    .replace(HEADING, "")
    .replace(BULLET, "")
    .trim()

private val FENCED_CODE = Regex("```[\\s\\S]*?```")
private val INLINE_CODE = Regex("`([^`]*)`")
private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val EMPHASIS = Regex("""\*{1,2}([^*]+)\*{1,2}""")
private val HEADING = Regex("^#{1,6}\\s*", RegexOption.MULTILINE)
private val BULLET = Regex("^\\s*[-*]\\s+", RegexOption.MULTILINE)

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

package io.github.alpharomercoma.openweights.core.common.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The three settings a speech model reads, and the eight it does not.
 *
 * These assertions are a transcription of `mtmd_helper_gen_audio_inp` in the vendored
 * engine, so a llama.cpp bump that adds a field to that struct should fail here and be
 * answered by adding the control, not by editing the expectation.
 */
class OutputModalityTest {
    @Test
    fun `a text model reads every setting`() {
        Tunable.entries.forEach {
            assertThat(OutputModality.TEXT.accepts(it)).isTrue()
        }
    }

    @Test
    fun `a speech model reads only the three the audio struct carries`() {
        val read = Tunable.entries.filter { OutputModality.SPEECH.accepts(it) }
        assertThat(read).containsExactly(Tunable.TOP_K, Tunable.TOP_P, Tunable.SEED)
    }

    @Test
    fun `temperature does not reach a speech model`() {
        // The one most likely to be added back by reflex. There is no temperature field in
        // the audio input struct, so a slider for it would move nothing.
        assertThat(OutputModality.SPEECH.accepts(Tunable.TEMPERATURE)).isFalse()
    }

    @Test
    fun `neither prompt reaches a speech model`() {
        // No chat template is rendered for speech: the prompt is the text to say.
        assertThat(OutputModality.SPEECH.accepts(Tunable.SYSTEM_PROMPT)).isFalse()
        assertThat(OutputModality.SPEECH.accepts(Tunable.TOOL_PROMPT)).isFalse()
    }

    @Test
    fun `there is no modality for something the engine cannot generate`() {
        // llama.cpp generates no images and no video. An enum case for either would be a
        // promise the engine cannot keep, and every exhaustive when would have to fake it.
        assertThat(OutputModality.entries.map { it.name })
            .containsExactly("TEXT", "SPEECH")
    }
}

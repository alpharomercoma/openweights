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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a reply sounds like.
 *
 * Markdown read literally is unbearable — a synthesiser says "asterisk asterisk" and
 * spells out every character of a code block — so the visual furniture is stripped before
 * the words reach the engine.
 */
class SpeechTextTest {

    @Test
    fun `a code block is announced rather than spelled out`() {
        val spoken = "Try this:\n```kotlin\nval x = 1\n```\nThat is all.".forSpeech()

        assertThat(spoken).doesNotContain("val x")
        assertThat(spoken).contains("code sample")
    }

    @Test
    fun `inline code keeps its words and loses its backticks`() {
        assertThat("Call `loadModel` first.".forSpeech()).isEqualTo("Call loadModel first.")
    }

    @Test
    fun `a link is read as its text, not its address`() {
        val spoken = "See [the docs](https://example.com/a/b).".forSpeech()

        assertThat(spoken).isEqualTo("See the docs.")
    }

    @Test
    fun `headings and bullets lose their marks`() {
        val spoken = "# Results\n- first\n- second".forSpeech()

        assertThat(spoken).isEqualTo("Results\nfirst\nsecond")
    }

    @Test
    fun `emphasis is dropped but the emphasised words stay`() {
        assertThat("This is **very** important.".forSpeech())
            .isEqualTo("This is very important.")
    }

    @Test
    fun `plain prose is left exactly as it was`() {
        val prose = "The KV cache stores keys and values for previous tokens."

        assertThat(prose.forSpeech()).isEqualTo(prose)
    }
}

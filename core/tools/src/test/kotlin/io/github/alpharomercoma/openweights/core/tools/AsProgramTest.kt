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

package io.github.alpharomercoma.openweights.core.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Getting the program out of what a chat model actually sent.
 *
 * The failure this prevents is not a near miss. A fenced block reaches the engine as a
 * tagged template literal, so the error is `TypeError: not a function`, which names nothing
 * the model did and sends it looking for a function it never wrote.
 */
class AsProgramTest {
    @Test
    fun `a fenced block is unwrapped`() {
        assertThat("```javascript\nconst x = 1;\nx\n```".asProgram())
            .isEqualTo("const x = 1;\nx")
    }

    @Test
    fun `a fence with no language is unwrapped too`() {
        assertThat("```\n40 + 2\n```".asProgram()).isEqualTo("40 + 2")
    }

    @Test
    fun `prose after the closing fence goes with it`() {
        assertThat("```js\n40 + 2\n```\nThat returns 42.".asProgram()).isEqualTo("40 + 2")
    }

    @Test
    fun `an unclosed fence is still unwrapped`() {
        // A reply cut off by the token limit. The program is all there; the fence is not.
        assertThat("```js\nconst a = 1;\na".asProgram()).isEqualTo("const a = 1;\na")
    }

    @Test
    fun `a program that merely contains backticks is left alone`() {
        // The conservative half. Only a fence on the first line is a fence; a template
        // literal in the middle of a working program is part of the program.
        val source = "const name = 'x';\nconst s = `hi \${name}`;\ns"
        assertThat(source.asProgram()).isEqualTo(source)
    }

    @Test
    fun `curly quotes become quotes the engine can read`() {
        // Measured as a real failure mode: the engine says "unexpected character", which
        // does not tell the model which character or where.
        assertThat("const s = “hello”;\ns".asProgram())
            .isEqualTo("const s = \"hello\";\ns")
    }

    @Test
    fun `an ordinary program is returned unchanged apart from trimming`() {
        assertThat("  const a = 1;\na  ".asProgram()).isEqualTo("const a = 1;\na")
    }

    @Test
    fun `a fenced block that also uses curly quotes gets both`() {
        assertThat("```js\nconst s = ‘hi’;\ns\n```".asProgram())
            .isEqualTo("const s = 'hi';\ns")
    }
}

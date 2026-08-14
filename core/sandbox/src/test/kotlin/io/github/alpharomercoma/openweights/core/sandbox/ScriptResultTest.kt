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

package io.github.alpharomercoma.openweights.core.sandbox

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one thing that crosses the process boundary, and what happens if it is read wrongly.
 *
 * Two values have to come back through a call that returns one string, so the answer carries
 * a mark saying which it is. That is a small enough trick to get subtly wrong and a bad
 * enough thing to get wrong: read the mark the wrong way round and every failed script is
 * reported to the model as having succeeded, with its error message as the result.
 *
 * On the host because none of it is Android. The interpreter has its own tests, on a device,
 * where it actually runs.
 */
class ScriptResultTest {
    @Test
    fun `an answer survives the trip out and back`() {
        val result = ScriptResult(output = "42", failed = false)

        assertThat(result.encode().decoded()).isEqualTo(result)
    }

    @Test
    fun `a failure survives it too`() {
        val result = ScriptResult(output = "SyntaxError: unexpected token", failed = true)

        assertThat(result.encode().decoded()).isEqualTo(result)
    }

    @Test
    fun `output that begins like a mark is not mistaken for one`() {
        // The reason the mark leads rather than trails. A script really can return a string
        // starting with an exclamation mark, and it must not come back as a failure.
        val result = ScriptResult(output = "!important", failed = false)

        assertThat(result.encode().decoded()).isEqualTo(result)
    }

    @Test
    fun `an empty answer is still an answer`() {
        val result = ScriptResult(output = "", failed = false)

        assertThat(result.encode().decoded()).isEqualTo(result)
    }
}

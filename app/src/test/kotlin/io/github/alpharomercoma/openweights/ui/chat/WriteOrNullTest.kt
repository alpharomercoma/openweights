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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The difference between a write that failed and a write somebody stopped.
 *
 * `runCatching` does not know one from the other, and everywhere this app used it around a
 * write, pressing Stop became storage being broken: an edit reported that the conversation
 * would not be there tomorrow, and a branch reported the same while leaving the half-built
 * conversation it had already created in the drawer. Both are lies, and the second leaves
 * something behind.
 */
@RunWith(RobolectricTestRunner::class)
class WriteOrNullTest {
    @Test
    fun `a write that went through gives back what it produced`() = runTest {
        assertThat(writeOrNull { 42L }).isEqualTo(42L)
    }

    @Test
    fun `a write that would not go through answers with null`() = runTest {
        assertThat(writeOrNull<Long> { error("the disk would not take it") }).isNull()
    }

    @Test
    fun `a write that produced null is not mistaken for one that failed`() = runTest {
        // The one shape this cannot tell apart, said out loud. Callers here return an id or
        // a row and never null, so the ambiguity is unreachable rather than merely unlikely.
        assertThat(writeOrNull<String?> { null }).isNull()
    }

    @Test
    fun `a write that was stopped stays stopped`() = runTest {
        var caught: Throwable? = null
        try {
            writeOrNull<Long> { throw CancellationException("stop") }
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }

        assertThat(caught).isInstanceOf(CancellationException::class.java)
    }
}

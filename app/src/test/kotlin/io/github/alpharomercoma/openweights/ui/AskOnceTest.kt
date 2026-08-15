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

package io.github.alpharomercoma.openweights.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When the app is allowed to ask about notifications.
 *
 * The rule used to be "in onCreate", which put a system dialog over the first frame of a
 * brand new install. It is now "when the waiting starts", and the difference is only worth
 * anything if somebody who never starts anything is never asked.
 */
@RunWith(RobolectricTestRunner::class)
class AskOnceTest {
    @get:Rule
    val compose = createComposeRule()

    private val waiting = MutableStateFlow(false)
    private var asks = 0

    @Test
    fun `nobody who only looks at the app is asked`() {
        compose.setContent { AskOnce(waiting) { asks++ } }
        compose.waitForIdle()

        assertThat(asks).isEqualTo(0)
    }

    @Test
    fun `the question arrives when the waiting does`() {
        compose.setContent { AskOnce(waiting) { asks++ } }
        compose.waitForIdle()

        waiting.value = true
        compose.waitForIdle()

        assertThat(asks).isEqualTo(1)
    }

    @Test
    fun `a second reply is not a second question`() {
        compose.setContent { AskOnce(waiting) { asks++ } }
        compose.waitForIdle()

        // Send, wait, answer, send again: the second turn must not ask a person who has
        // already said no, and the system stops showing the dialog after two refusals
        // whatever we do, so a loop here would spend the app's one remaining chance.
        repeat(3) {
            waiting.value = true
            compose.waitForIdle()
            waiting.value = false
            compose.waitForIdle()
        }

        assertThat(asks).isEqualTo(1)
    }
}

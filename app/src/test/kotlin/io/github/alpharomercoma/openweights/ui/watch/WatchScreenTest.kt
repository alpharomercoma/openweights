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

package io.github.alpharomercoma.openweights.ui.watch

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.alpharomercoma.openweights.core.common.context.Watch
import io.github.alpharomercoma.openweights.core.common.context.WatchState
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a watch says about itself while it is running.
 *
 * The complaint this answers was that a watch was opaque: it existed, and nothing on screen
 * said how far along it was, when it would look again, or whether it would ever stop. Each
 * of those is a phrase here, and each of them is a promise the code has to keep.
 */
@RunWith(RobolectricTestRunner::class)
class WatchScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()

    @Test
    fun `a running watch says which check is next and when`() {
        show(
            Watch(
                id = 1,
                task = "Check the tide",
                everyMinutes = 5,
                createdAt = now,
                runs = 3,
                nextRunAt = now + 2 * MINUTE + 14 * SECOND,
            ),
        )

        compose.onNodeWithText("check 4 of ${Watch.MAX_RUNS}", substring = true).assertExists()
        // Minutes, not the exact second: the screen reads its own clock, which has moved
        // on a little by the time it composes, and pinning the second would make this test
        // fail once in every sixty runs for a reason that is not a defect.
        compose.onNodeWithText("next in 2m", substring = true).assertExists()
    }

    @Test
    fun `a running watch says when it will stop on its own`() {
        // The question that started this: "will it go on forever in the background?"
        show(Watch(id = 1, task = "Check the tide", everyMinutes = 60, createdAt = now))

        compose.onNodeWithText("stops in", substring = true).assertExists()
    }

    @Test
    fun `a check whose moment has passed is owed rather than promised`() {
        // Doze delays a scheduled tick and a busy engine skips one, so the interface must
        // not claim a time it cannot keep.
        show(
            Watch(
                id = 1,
                task = "Check the tide",
                everyMinutes = 5,
                createdAt = now,
                nextRunAt = now - MINUTE,
            ),
        )

        compose.onNodeWithText("next check due now", substring = true).assertExists()
    }

    @Test
    fun `a watch that ended on its own says so instead of counting down`() {
        show(
            Watch(
                id = 1,
                task = "Check the tide",
                everyMinutes = 5,
                state = WatchState.EXPIRED,
                createdAt = now,
                runs = Watch.MAX_RUNS,
            ),
        )

        compose.onNodeWithText("Finished on its own after ${Watch.MAX_RUNS} checks")
            .assertExists()
        compose.onNodeWithText("next in", substring = true).assertDoesNotExist()
    }

    private fun show(vararg watches: Watch) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                WatchScreen(watches = watches.toList(), onStop = {}, onForget = {})
            }
        }
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60_000L
    }
}

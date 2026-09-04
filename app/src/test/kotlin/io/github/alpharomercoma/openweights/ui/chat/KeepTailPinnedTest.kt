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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.designsystem.component.KeepTailPinned
import io.github.alpharomercoma.openweights.core.designsystem.component.LocalFollowTail
import io.github.alpharomercoma.openweights.core.designsystem.component.rememberFollowTailState
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rule a disclosure inside the transcript must not break when it asks to stay pinned.
 *
 * Opening a tool step, a reasoning block or the work list makes a list item taller, and the
 * scroll correction that keeps the newest reply against the bottom of the screen is only
 * ever requested from a recomposition. None of those disclosures recomposes anything above
 * itself, so they now ask for the correction directly. That hands a composable buried inside
 * a list item the ability to move the whole list, which is the part worth a test: a reader
 * who has scrolled up to read something is exactly the person a stray jump to the bottom
 * would rob, and expanding a step up there must leave them where they are.
 *
 * What is deliberately not asserted here is the frame in between. The list corrects itself
 * one frame later even without the fix, when `isAtBottom` flips and the state holder
 * recomposes, so the settled position is identical either way and every assertion available
 * to a Robolectric test is a settled one: `waitForIdle` runs past the bad frame by
 * definition, and `advanceTimeByFrame` recomposes to stability inside the frame it advances.
 * Both were tried against a deliberately broken build and both passed it. The visible defect
 * is the transient, and it stays a thing to watch on a device rather than a claim made here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class KeepTailPinnedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a section opening while the reader is at the bottom keeps them there`() {
        var expanded by mutableStateOf(false)
        lateinit var listState: LazyListState
        compose.setContent { OpenWeightsTheme(dynamicColor = false) { listState = list(expanded) } }
        compose.waitForIdle()
        assertThat(listState.atBottom()).isTrue()

        expanded = true
        compose.waitForIdle()

        assertThat(listState.atBottom()).isTrue()
    }

    @Test
    fun `a section opening while the reader has scrolled up does not drag them down`() {
        // The risk the mechanism introduces. A disclosure several layers inside a list item
        // can now move the list, and the one thing it must never do with that is haul
        // somebody off the paragraph they went back to read.
        var expanded by mutableStateOf(false)
        lateinit var listState: LazyListState
        compose.setContent { OpenWeightsTheme(dynamicColor = false) { listState = list(expanded) } }
        compose.waitForIdle()

        // A real drag, not a programmatic scroll: detaching is driven by the drag
        // interaction, so scrollToItem would leave the list still following and prove
        // nothing about the case this guards.
        compose.onNodeWithTag(LIST).performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertThat(listState.atBottom()).isFalse()
        val restingIndex = listState.firstVisibleItemIndex

        expanded = true
        compose.waitForIdle()

        assertThat(listState.atBottom()).isFalse()
        assertThat(listState.firstVisibleItemIndex).isEqualTo(restingIndex)
    }

    /** A transcript-shaped list: a screenful of turns, then one that can grow. */
    @Composable
    private fun list(expanded: Boolean): LazyListState {
        val listState = rememberLazyListState()
        val tail = rememberFollowTailState(listState, rememberCoroutineScope())
        CompositionLocalProvider(LocalFollowTail provides tail) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(LIST),
            ) {
                items((1..20).toList()) { Filler(TURN_DP) }
                item {
                    Column {
                        KeepTailPinned(expanded)
                        // Far taller than the screen, so a growth that goes uncorrected
                        // cannot be mistaken for one that was absorbed.
                        if (expanded) Filler(GROWTH_DP)
                        Text("the newest reply")
                    }
                }
            }
        }
        return listState
    }
}

@Composable
private fun Filler(dp: Int) {
    Box(modifier = Modifier.fillMaxWidth().height(dp.dp))
}

/**
 * Whether the end of the content is at the end of the viewport.
 *
 * The same question the state holder asks itself, at the same tolerance: exact pixel
 * equality is fragile across densities and says nothing more than this does.
 */
private fun LazyListState.atBottom(): Boolean {
    val last = layoutInfo.visibleItemsInfo.lastOrNull() ?: return false
    return last.index == layoutInfo.totalItemsCount - 1 &&
        last.offset + last.size <= layoutInfo.viewportEndOffset + TOLERANCE_PX
}

private const val LIST = "transcript"
private const val TURN_DP = 80
private const val GROWTH_DP = 1200
private const val TOLERANCE_PX = 24

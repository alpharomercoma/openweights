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

package io.github.alpharomercoma.openweights.core.designsystem.component

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Scroll behaviour for a list that grows while you are reading it.
 *
 * The rule every chat app converged on: follow the newest content only while the reader is
 * already at the bottom. The moment they scroll up, stop — they are reading something and
 * yanking them back down makes streaming output unreadable. Offer a way back, and resume
 * following when they take it or scroll back down themselves.
 */
@Stable
class FollowTailState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    internal var isFollowing by mutableStateOf(true)

    /** True when the reader has scrolled away and is no longer being followed down. */
    val isDetached: Boolean get() = !isFollowing

    /** Jumps to the newest content and starts following again. */
    fun jumpToLatest() {
        isFollowing = true
        scope.launch { listState.scrollToBottom() }
    }
}

/**
 * Remembers follow-tail behaviour for [listState] and drives it from [contentSignal].
 *
 * @param contentSignal any value that changes when new content is appended — the streamed
 *   text itself works well, since it changes on every token.
 */
@Composable
fun rememberFollowTailState(
    listState: LazyListState,
    contentSignal: Any?,
    scope: CoroutineScope,
): FollowTailState {
    val state = remember(listState) { FollowTailState(listState, scope) }

    val isAtBottom by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf true
            last.index == layout.totalItemsCount - 1 &&
                last.offset + last.size <= layout.viewportEndOffset + BOTTOM_TOLERANCE_PX
        }
    }

    // Any touch-initiated scroll detaches; if it ends up at the bottom anyway, the effect
    // below re-attaches, so dragging within the last screenful behaves as expected.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) state.isFollowing = false }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) state.isFollowing = true
    }

    LaunchedEffect(contentSignal, state.isFollowing) {
        if (state.isFollowing) listState.scrollToBottom()
    }

    return state
}

/**
 * Scrolls to the very end of the content, including the tail of an item taller than the
 * viewport — which a long streamed reply usually is.
 */
internal suspend fun LazyListState.scrollToBottom() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    scrollToItem(lastIndex)
    // scrollToItem lands on the item's start; scrollBy clamps at the end of the list.
    scrollBy(OVERSCROLL_PX)
}

/** Treat "within a few pixels of the end" as being at the bottom; exact equality is fragile. */
private const val BOTTOM_TOLERANCE_PX = 24

/** Larger than any plausible message; scrollBy clamps, so this just means "to the end". */
private const val OVERSCROLL_PX = 100_000f

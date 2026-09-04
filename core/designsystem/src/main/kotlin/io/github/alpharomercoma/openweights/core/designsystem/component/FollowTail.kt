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

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Scroll behaviour for a list that grows while you are reading it.
 *
 * The rule every chat app converged on: follow the newest content only while the reader is
 * already at the bottom. The moment they scroll up, stop. They are reading something and
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
        scope.launch { listState.pinToBottom() }
    }

    /**
     * Asks for the tail to stay at the bottom through a size change happening this frame.
     *
     * Idempotent and cheap: [pinToBottom] records a request that the next measure pass
     * applies, so calling it twice in a frame is the same as calling it once. Gated on no
     * scroll being in progress for the same reason the effect below is, which is that a
     * drag or a fling must never be fought.
     */
    internal fun keepPinned() {
        if (isFollowing && !listState.isScrollInProgress) listState.pinToBottom()
    }
}

/**
 * The follow-tail state of the list the content is inside, when it is inside one.
 *
 * Provided so that a composable which changes its own height can say so. Everything about
 * the pin below is driven by the caller of [rememberFollowTailState] recomposing, and a
 * disclosure opening inside a list item does not recompose that caller: it recomposes
 * itself, several layers down, and the list simply measures taller. Without this the growth
 * lands with no pin request pending, the tail goes under the fold, and the correction
 * arrives a frame later once `isAtBottom` has noticed. See [KeepTailPinned].
 */
val LocalFollowTail = staticCompositionLocalOf<FollowTailState?> { null }

/**
 * Holds the tail in place across a size change this composable is about to make.
 *
 * Call it from the composable that owns the disclosure, passing the flag that opens it. The
 * flag is the whole mechanism and not decoration: a composable is recomposed for the state
 * it reads, and `expanded` read only inside a `Column`'s content lambda recomposes that
 * lambda alone. Taking it as a parameter moves the read up into the caller's own scope, so
 * the frame that recomposes on a tap is the frame that carries the pin request, and the
 * growth and the correction land in the same measure pass.
 *
 * [DisposableEffect] rather than [SideEffect] for the same reason it is keyed: this needs to
 * fire when the height changes and not on every unrelated recomposition of the block. Its
 * effect runs while changes are applied, which is before this frame's layout, so the request
 * is pending by the time the taller content measures. A [LaunchedEffect] would not do: its
 * coroutine can land after the frame that grew has already been drawn, which is precisely
 * the bob this exists to remove.
 *
 * Null outside a following list, where it does nothing, which is what a preview and a
 * sheet want.
 *
 * @param expanded whatever decides this composable's height.
 */
@Composable
fun KeepTailPinned(expanded: Boolean) {
    val tail = LocalFollowTail.current
    DisposableEffect(expanded, tail) {
        tail?.keepPinned()
        onDispose {}
    }
}

/**
 * Remembers follow-tail behaviour for [listState].
 *
 * The caller's recomposition is the content signal: this composable must sit in the
 * scope that recomposes when the list's content changes, which a chat screen reading
 * its transcript does on every streamed flush.
 */
@Composable
fun rememberFollowTailState(listState: LazyListState, scope: CoroutineScope): FollowTailState {
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

    // Only a real drag detaches. Watching isScrollInProgress instead, as this first did,
    // also fires for the scroll this component performs itself, so following the tail
    // switched following off, the check below switched it back on, and the two fought each
    // other for every token of a streamed reply.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) state.isFollowing = false
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) state.isFollowing = true
    }

    // A SideEffect rather than an effect keyed on the content, because an effect's
    // coroutine can land after the frame that grew the item has already measured — and
    // that frame then shows the tail pushed below the fold before the correction, the
    // residual one-line bob the recording still caught a few times a minute. SideEffect
    // runs before this frame's layout, so whenever a recomposition grew the list, the
    // pin request is already pending by the time the growth measures: one frame, one
    // motion. Gated on no scroll being in progress so a drag or fling is never fought;
    // the pin request itself is not a scroll session, so it never gates itself out.
    SideEffect { state.keepPinned() }

    return state
}

/**
 * Pins the end of the content to the end of the viewport, atomically with layout.
 *
 * The predecessor measured how far the tail hung below the fold and scrolled by that
 * much — after the frame had already been drawn. So every time streaming text wrapped a
 * new line, the activity row under it was pushed below the fold for a frame or two, and
 * the correction then pulled the whole page up: a one-line down-then-up bob per wrapped
 * line, at exactly the place the reader is looking. Filmed on the phone at 20 fps and
 * measured as direction reversals in the scroll about every half second of a
 * viewport-filling reply.
 *
 * requestScrollToItem instead records the request and applies it during the next measure
 * pass, so the grown item and the corrected scroll land in the same frame: the page
 * ratchets upward cleanly and never dips. The absurd offset asks for the last item's
 * start to sit far above the viewport; the measure pass clamps that to the largest
 * legal scroll, which is precisely "content end at viewport end" — for a tail item
 * shorter than the screen and for one far taller than it alike.
 */
internal fun LazyListState.pinToBottom() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    requestScrollToItem(lastIndex, scrollOffset = PIN_TO_END_OFFSET)
}

/** Treat "within a few pixels of the end" as being at the bottom; exact equality is fragile. */
private const val BOTTOM_TOLERANCE_PX = 24

/** Far past any real item height; the measure pass clamps it to "content end at viewport end". */
private const val PIN_TO_END_OFFSET = Int.MAX_VALUE / 2

/**
 * Whether enough is hidden below to be worth offering a jump.
 *
 * The pill used to appear as soon as the list was detached, which on a short conversation
 * meant it appeared over a bottom that was already visible. Measured against what is left
 * below the viewport rather than against the item count, because one long reply and ten
 * short ones hide different amounts.
 */
@Composable
fun LazyListState.hasHiddenTail(): Boolean {
    val hidden by remember(this) {
        derivedStateOf {
            val layout = layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            val isLastItem = last.index == layout.totalItemsCount - 1
            val overshoot = last.offset + last.size - layout.viewportEndOffset
            // Either there are whole items below, or the last one runs well past the fold.
            !isLastItem || overshoot > HIDDEN_TAIL_THRESHOLD_PX
        }
    }
    return hidden
}

/**
 * How much has to be out of sight before the jump is offered, in pixels.
 *
 * Roughly a third of a phone screen. Below this the reader can see where they are and a
 * button telling them to go there is noise.
 */
private const val HIDDEN_TAIL_THRESHOLD_PX = 700

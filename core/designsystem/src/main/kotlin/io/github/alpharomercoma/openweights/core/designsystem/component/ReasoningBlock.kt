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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.theme.Motion
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import java.util.Locale

/**
 * A model's chain of thought, collapsed by default.
 *
 * Reasoning models emit far more thinking than answer, and an expanded block pushes the
 * actual reply off the screen. Every assistant that ships a reasoning model: ChatGPT,
 * Claude, Gemini: collapses it behind a one-line summary for the same reason. The block
 * stays available because for a local model the thinking is often the interesting part.
 *
 * @param reasoning the text of the thought so far.
 * @param isInProgress true while the model is still thinking, which changes the label from
 *   a completed duration to a live "Thinking" state.
 * @param durationMs how long thinking took, once it has finished.
 */
@Composable
fun ReasoningBlock(
    reasoning: String,
    isInProgress: Boolean,
    modifier: Modifier = Modifier,
    durationMs: Long? = null,
) {
    // Open while the model is thinking, shut once it has finished, and a tap wins in
    // either state.
    //
    // It used to be collapsed always and never move, which failed in both directions at
    // once: a reader who never tapped never saw the thinking at all, and one who did tap
    // was left with a finished chain of thought sitting at full height above the answer
    // that arrived under it. Watching a model think is worth seeing live and worth nothing
    // afterwards, so it follows the generation by default.
    //
    // The override clears when thinking ends, which is deliberate. A block opened by hand
    // while the model was working still folds away when it stops, because the reason to
    // have it open has gone; the tap is about the next few seconds, not forever. Saveable
    // and keyed to the reasoning, so scrolling the turn off screen does not reopen it.
    var override by rememberSaveable(reasoning) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isInProgress) { if (!isInProgress) override = null }
    val expanded = override ?: isInProgress

    // In the frame the tap recomposes, so the reply under this block does not move
    // when the reasoning above it opens. See KeepTailPinned.
    KeepTailPinned(expanded)

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.quick(),
        label = "chevron",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { override = !expanded }
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Caption(text = reasoningLabel(isInProgress, durationMs))
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Hide reasoning" else "Show reasoning",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(chevronRotation),
            )
        }

        // Not animated, and KeepTailPinned above is why it does not need to be: both
        // exist so the growth and the scroll correction land in one frame. See
        // KeepTailPinned.
        if (expanded) {
            // Readable, and only here at all once the block has been opened. This carried a
            // clearAndSetSemantics {} on the grounds that the header announced the same
            // thing, which the header does not: it says how long thinking took and offers
            // to show it. So the one control whose whole purpose is to reveal this text
            // revealed nothing to anybody using a screen reader. Nothing is announced
            // twice, because the collapsed block does not compose this at all.
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            )
        }
    }
}

private fun reasoningLabel(isInProgress: Boolean, durationMs: Long?): String = when {
    isInProgress -> "Thinking…"
    durationMs == null -> "Thought about it"
    durationMs < MILLIS_PER_SECOND ->
        "Thought for ${durationMs}ms"
    else ->
        String.format(Locale.getDefault(), "Thought for %.1fs", durationMs / MILLIS_PER_SECOND)
}

private const val MILLIS_PER_SECOND = 1000.0

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun ReasoningBlockPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            ReasoningBlock(
                reasoning = "The user wants a short definition. Keep it to one sentence.",
                isInProgress = false,
                durationMs = 3200,
            )
            ReasoningBlock(reasoning = "Working through it", isInProgress = true)
        }
    }
}

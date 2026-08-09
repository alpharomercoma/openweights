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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "chevron",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Metric(text = reasoningLabel(isInProgress, durationMs))
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Hide reasoning" else "Show reasoning",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(chevronRotation),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 2.dp, bottom = 8.dp)
                    // The header already announces this content; reading it twice is noise.
                    .clearAndSetSemantics {},
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

@Preview(showBackground = true, backgroundColor = 0xFF0B0D0F)
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

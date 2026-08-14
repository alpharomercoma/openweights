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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius

/**
 * The question the model is waiting on.
 *
 * Directly above the composer, because that is where the user is looking and where the
 * answer belongs. It names the tool and shows the arguments verbatim: approving a tool
 * without seeing what it was asked to do is not approval.
 */
@Composable
fun ToolApproval(call: ToolCall, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Run ${call.name}?",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = call.argumentsJson.ifBlank { "with no arguments" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = ARGUMENT_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Declining is not an error: the model is told, and answers without the tool.
            TextButton(onClick = { onAnswer(false) }) { Text("Not now") }
            Button(
                onClick = { onAnswer(true) },
                shape = RoundedCornerShape(Radius.sm),
            ) {
                Text("Run")
            }
        }
    }
}

/** Enough to read a query or a URL, not enough for a page of JSON to take the screen. */
/**
 * Enough lines to show the whole of anything that would leave the device.
 *
 * Four cut a long argument off with an ellipsis, which is tolerable for a file being saved
 * on the device and not tolerable for a search query: the reason that card is being shown at
 * all can be the passage hidden behind the dots. A search is capped at a hundred and twenty
 * characters, so twelve lines shows all of one with room to spare.
 */
private const val ARGUMENT_LINES = 12

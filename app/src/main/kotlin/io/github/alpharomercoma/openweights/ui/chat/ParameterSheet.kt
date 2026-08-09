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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Per-model generation settings.
 *
 * Each control says what it does in a sentence rather than naming the paper it came from:
 * these knobs are famously opaque, and a local-model app is exactly where someone will
 * meet them for the first time. Values are saved against the model, because the right
 * temperature for one is wrong for another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParameterSheet(
    modelName: String,
    preferences: ModelPreferences,
    onSave: (ModelPreferences) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(preferences) { mutableStateOf(preferences) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(modelName, style = MaterialTheme.typography.titleMedium)
            Metric("settings saved for this model only")

            Setting(
                label = "Temperature",
                explanation = "Lower is more predictable, higher is more varied. 0 always " +
                    "picks the most likely next word.",
                value = String.format(Locale.getDefault(), "%.2f", draft.temperature),
            ) {
                Slider(
                    value = draft.temperature,
                    onValueChange = { draft = draft.copy(temperature = it) },
                    valueRange = 0f..MAX_TEMPERATURE,
                )
            }

            Setting(
                label = "Top-p",
                explanation = "Considers only the most likely words that together make up " +
                    "this much of the probability.",
                value = String.format(Locale.getDefault(), "%.2f", draft.topP),
            ) {
                Slider(
                    value = draft.topP,
                    onValueChange = { draft = draft.copy(topP = it) },
                    valueRange = MIN_TOP_P..1f,
                )
            }

            Setting(
                label = "Top-k",
                explanation = "Hard limit on how many candidate words are considered at all.",
                value = draft.topK.toString(),
            ) {
                Slider(
                    value = draft.topK.toFloat(),
                    onValueChange = { draft = draft.copy(topK = it.roundToInt()) },
                    valueRange = 0f..MAX_TOP_K,
                )
            }

            Setting(
                label = "Repeat penalty",
                explanation = "Discourages repeating itself. Too high and it avoids words " +
                    "it genuinely needs.",
                value = String.format(Locale.getDefault(), "%.2f", draft.repeatPenalty),
            ) {
                Slider(
                    value = draft.repeatPenalty,
                    onValueChange = { draft = draft.copy(repeatPenalty = it) },
                    valueRange = MIN_REPEAT_PENALTY..MAX_REPEAT_PENALTY,
                )
            }

            Setting(
                label = "Context length",
                explanation = "How much conversation the model keeps in mind. Costs memory, " +
                    "and takes effect the next time the model loads.",
                value = "${draft.contextLength} tokens",
            ) {
                Slider(
                    value = draft.contextLength.toFloat(),
                    onValueChange = { draft = draft.copy(contextLength = it.roundToInt()) },
                    valueRange = MIN_CONTEXT..MAX_CONTEXT,
                    steps = CONTEXT_STEPS,
                )
            }

            Column {
                Text("System prompt", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Standing instructions sent before every conversation. Small " +
                        "models follow explicit ones far better than implied ones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.systemPrompt,
                    onValueChange = { draft = draft.copy(systemPrompt = it) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    placeholder = { Text("Optional") },
                    minLines = 2,
                    maxLines = 5,
                    shape = RoundedCornerShape(Radius.sm),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(draft) }) { Text("Save") }
                TextButton(onClick = onReset) { Text("Reset to defaults") }
            }
        }
    }
}

@Composable
private fun Setting(
    label: String,
    explanation: String,
    value: String,
    control: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Metric(value)
        }
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        control()
    }
}

private const val MAX_TEMPERATURE = 2f
private const val MIN_TOP_P = 0.1f
private const val MAX_TOP_K = 100f
private const val MIN_REPEAT_PENALTY = 1f
private const val MAX_REPEAT_PENALTY = 1.5f
private const val MIN_CONTEXT = 1024f
private const val MAX_CONTEXT = 32_768f
private const val CONTEXT_STEPS = 30

@Preview(showBackground = true, backgroundColor = 0xFF0A0E11)
@Composable
private fun ParameterSheetPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        ParameterSheet(
            modelName = "LFM2.5-2.6B-Q4_K_M",
            preferences = ModelPreferences(),
            onSave = {},
            onReset = {},
            onDismiss = {},
        )
    }
}

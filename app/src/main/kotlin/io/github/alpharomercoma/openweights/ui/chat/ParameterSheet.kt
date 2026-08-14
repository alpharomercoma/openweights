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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.ReasoningEffort
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
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
    supportsThinking: Boolean,
    hasGpu: Boolean,
    onSave: (ModelPreferences) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(preferences) { mutableStateOf(preferences) }
    // Read from the composition, not Locale.getDefault(): a screen formatted with the
    // latter keeps the old decimal separator after the user changes language, because
    // nothing tells it to recompose.
    val locale = LocalConfiguration.current.locales[0]

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

            if (supportsThinking) {
                ThinkingSetting(
                    draft = draft,
                    onChange = { draft = it },
                )
            }

            Setting(
                label = "Temperature",
                explanation = "Lower is more predictable, higher is more varied. 0 always " +
                    "picks the most likely next word.",
                value = String.format(locale, "%.2f", draft.temperature),
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
                value = String.format(locale, "%.2f", draft.topP),
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
                    "it needs.",
                value = String.format(locale, "%.2f", draft.repeatPenalty),
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
                    valueRange = CONTEXT_RANGE,
                    steps = ModelLoadParams.CONTEXT_STEPS,
                )
            }

            if (hasGpu) {
                Setting(
                    label = "Run on the GPU",
                    explanation = "Reads a prompt about five times faster and writes an " +
                        "answer about a third slower, so it suits questions that need " +
                        "looking things up more than long replies. Costs battery, and " +
                        "takes effect the next time the model loads.",
                    value = if (draft.gpuLayers > 0) "on" else "off",
                ) {
                    Switch(
                        checked = draft.gpuLayers > 0,
                        onCheckedChange = {
                            // All the layers or none. Anything between splits every token
                            // across two processors and pays the transfer both ways, which
                            // measured slower than either on its own.
                            draft = draft.copy(gpuLayers = if (it) ALL_LAYERS else 0)
                        },
                    )
                }
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

            Column {
                Text("Tool instructions", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Sent with the tools, before your own prompt above. This is the " +
                        "whole of what the app adds: nothing else is sent that you cannot " +
                        "see here. Empty it and the model is told nothing about its tools.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft.toolPrompt,
                    onValueChange = { draft = draft.copy(toolPrompt = it) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    placeholder = { Text("Nothing about tools") },
                    minLines = 3,
                    maxLines = 8,
                    shape = RoundedCornerShape(Radius.sm),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
                TextButton(
                    onClick = {
                        draft = draft.copy(toolPrompt = ModelPreferences.DEFAULT_TOOL_PROMPT)
                    },
                ) {
                    Text("Restore the default wording")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(draft) }) { Text("Save") }
                TextButton(onClick = onReset) { Text("Reset to defaults") }
            }
        }
    }
}

/**
 * Thinking, and how much of it.
 *
 * Only shown when the loaded chat template understands the flag, which llama.cpp can tell
 * us. Reasoning costs tens of seconds a reply on a phone, so this is a speed control as
 * much as a quality one. The effort level is passed to the template for the models that
 * read one and ignored by the rest, so it stays available whenever thinking is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingSetting(draft: ModelPreferences, onChange: (ModelPreferences) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Thinking", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Work through the problem before answering. Slower, and better " +
                        "on anything with steps in it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = draft.thinking,
                onCheckedChange = { onChange(draft.copy(thinking = it)) },
            )
        }

        if (draft.thinking) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ReasoningEffort.entries.forEachIndexed { index, effort ->
                    SegmentedButton(
                        selected = draft.reasoningEffort == effort.name,
                        onClick = { onChange(draft.copy(reasoningEffort = effort.name)) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index,
                            ReasoningEffort.entries.size,
                        ),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            activeBorderColor = MaterialTheme.colorScheme.primary,
                            inactiveBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                        label = { Text(effort.label, maxLines = 1) },
                    )
                }
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

/** More layers than any model has, which is llama.cpp's way of saying all of them. */
private const val ALL_LAYERS = 99

@Preview(showBackground = true, backgroundColor = 0xFF0B0D0F)
@Composable
private fun ParameterSheetPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        ParameterSheet(
            modelName = "LFM2.5-2.6B-Q4_K_M",
            preferences = ModelPreferences(),
            supportsThinking = true,
            hasGpu = true,
            onSave = {},
            onReset = {},
            onDismiss = {},
        )
    }
}

/** The context lengths a user may pick, as the slider wants them. */
private val CONTEXT_RANGE =
    ModelLoadParams.MIN_CONTEXT_LENGTH.toFloat()..ModelLoadParams.MAX_CONTEXT_LENGTH.toFloat()

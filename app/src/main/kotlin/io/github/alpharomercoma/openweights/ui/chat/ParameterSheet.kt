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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.common.model.AnswerLength
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.ReasoningEffort
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.data.Offload
import io.github.alpharomercoma.openweights.core.designsystem.component.AccentButton
import io.github.alpharomercoma.openweights.core.designsystem.component.Caption
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.component.StepSlider
import io.github.alpharomercoma.openweights.core.designsystem.theme.Motion
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsColors
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
    /** Where the weights of the model currently loaded actually are, largest buffer first. */
    offloadBuffers: List<Pair<String, Int>> = emptyList(),
    /** The window the model is actually running with, shown while the setting is automatic. */
    loadedContext: Int = ModelLoadParams.DEFAULT_CONTEXT_LENGTH,
    onSave: (ModelPreferences) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(preferences) { mutableStateOf(preferences) }
    // Not rememberSaveable: the sheet is dismissed on rotation with everything else in it,
    // and a disclosure that survives its own container would only reopen on a sheet whose
    // draft had already gone back to the saved values.
    var advancedOpen by remember { mutableStateOf(false) }
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
            Caption("saved for this model only")

            if (supportsThinking) {
                ThinkingSetting(
                    draft = draft,
                    onChange = { draft = it },
                )
            }

            // Above temperature, because it is the one on this sheet a person who does not
            // know what a sampler is still wants.
            Setting(
                label = "Answer length",
                explanation = "How much the model writes when the question does not say.",
                value = AnswerLength.fromName(draft.answerLength).label,
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AnswerLength.entries.forEachIndexed { index, length ->
                        SegmentedButton(
                            selected = draft.answerLength == length.name,
                            onClick = { draft = draft.copy(answerLength = length.name) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                AnswerLength.entries.size,
                            ),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = OpenWeightsColors.Lime,
                                activeContentColor = OpenWeightsColors.Ink,
                                activeBorderColor = OpenWeightsColors.Lime,
                                inactiveBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                            label = { Text(length.label, maxLines = 1) },
                        )
                    }
                }
            }

            Setting(
                label = "Temperature",
                explanation = "Lower is steadier, higher is more varied.",
                value = String.format(locale, "%.2f", draft.temperature),
            ) {
                StepSlider(
                    value = draft.temperature,
                    onValueChange = { draft = draft.copy(temperature = it) },
                    valueRange = 0f..MAX_TEMPERATURE,
                    steps = 0,
                )
            }

            val isAutomatic = draft.contextLength == ModelPreferences.AUTOMATIC
            Setting(
                label = "Context length",
                // Careful with this sentence. It used to say "as much as the model was
                // trained for", which is the one thing the number is not: a file states how
                // far it can address, not how far it was trained, and the two differ by four
                // times on models this app recommends. Saying the wider thing would be the
                // app vouching for output it has no way to check.
                explanation = "How much conversation it keeps in mind. Left alone, as much " +
                    "as this phone can hold. Models are often poorer near the top of their " +
                    "range than the file admits. Applies at the next load.",
                // The loaded window when it is being chosen for you, because a slider reading
                // zero is not a setting anybody can act on, and the number that matters is
                // the one the model is actually running with.
                value = if (isAutomatic) {
                    "$loadedContext tokens, automatic"
                } else {
                    "${draft.contextLength} tokens"
                },
                footnote = "Move the slider to fix it, or reset to go back to automatic"
                    .takeIf { isAutomatic },
            ) {
                StepSlider(
                    value = (draft.contextLength.takeIf { it > 0 } ?: loadedContext).toFloat(),
                    onValueChange = { draft = draft.copy(contextLength = it.roundToInt()) },
                    // Up to what this model is actually running with rather than a constant.
                    // The constant was 32768, and automatic now opens LFM2.5 at 128000, so the
                    // sheet read "128000 tokens" beside a thumb pinned at the end of a shorter
                    // scale: one touch dropped the window by three quarters with no drag.
                    valueRange = contextRange(loadedContext),
                    steps = ModelLoadParams.CONTEXT_STEPS,
                )
            }

            Column {
                Text("System prompt", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Standing instructions, sent before every conversation. Small " +
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
                    colors = fieldColors(),
                )
            }

            // One disclosure rather than seventeen controls in a column.
            //
            // The sheet ran to about twelve hundred dp, and the three settings anybody
            // touches were scattered through it: temperature at the top, context length in
            // the middle, the system prompt near the bottom, each separated by two samplers
            // whose defaults are correct and whose names are from a paper. Everything still
            // here is still here, one tap away, which is the difference between simplifying
            // an interface and removing what a developer came for.
            AdvancedSettings(open = advancedOpen, onToggle = { advancedOpen = !advancedOpen }) {
                Setting(
                    label = "Top-p",
                    explanation = "Keeps the likeliest words, up to this share of the odds.",
                    value = String.format(locale, "%.2f", draft.topP),
                ) {
                    StepSlider(
                        value = draft.topP,
                        onValueChange = { draft = draft.copy(topP = it) },
                        valueRange = MIN_TOP_P..1f,
                        steps = 0,
                    )
                }

                Setting(
                    label = "Top-k",
                    explanation = "Never weighs up more candidates than this.",
                    value = draft.topK.toString(),
                ) {
                    StepSlider(
                        value = draft.topK.toFloat(),
                        onValueChange = { draft = draft.copy(topK = it.roundToInt()) },
                        valueRange = 0f..MAX_TOP_K,
                        steps = 0,
                    )
                }

                Setting(
                    label = "Repeat penalty",
                    explanation = "Discourages repeating itself. Too high and it dodges words " +
                        "it needs.",
                    value = String.format(locale, "%.2f", draft.repeatPenalty),
                ) {
                    StepSlider(
                        value = draft.repeatPenalty,
                        onValueChange = { draft = draft.copy(repeatPenalty = it) },
                        valueRange = MIN_REPEAT_PENALTY..MAX_REPEAT_PENALTY,
                        steps = 0,
                    )
                }
                if (hasGpu) {
                    Setting(
                        label = "Processor",
                        explanation = "Which processor holds the layers. Changing this " +
                            "reloads the model, which takes a few seconds. Your chat is " +
                            "kept.",
                        value = Offload.fromName(draft.offload).label.lowercase(),
                        // What the request actually produced, under the request itself.
                        // Asking for the GPU and getting it are different things: a backend
                        // that fails to attach loads onto the CPU and reports the layer
                        // count it was given regardless, so this is the only line in the app
                        // that can be checked.
                        footnote = offloadBuffers
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString(" · ") { (name, mib) -> "$name $mib MiB" }
                            ?.let { "loaded: $it" },
                    ) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            Offload.entries.forEachIndexed { index, choice ->
                                SegmentedButton(
                                    selected = Offload.fromName(draft.offload) == choice,
                                    onClick = { draft = draft.copy(offload = choice.name) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = Offload.entries.size,
                                    ),
                                ) {
                                    Text(choice.label)
                                }
                            }
                        }
                    }
                }
                Column {
                    Text("Tool instructions", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Sent with the tools, before your own prompt. This is the whole " +
                            "of what the app adds.",
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
                        colors = fieldColors(),
                    )
                    TextButton(
                        onClick = {
                            draft = draft.copy(toolPrompt = ModelPreferences.DEFAULT_TOOL_PROMPT)
                        },
                    ) {
                        Text("Restore the default wording")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton(onClick = { onSave(draft) }) { Text("Save") }
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
            // Spaced, not merely apart. SpaceBetween on its own puts the switch flush
            // against whatever the sentence beside it happens to end with, so a
            // description that filled its line ran into the control.
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Thinking", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Work it through first. Slower, and better on anything with " +
                        "steps in it.",
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
                            activeContainerColor = OpenWeightsColors.Lime,
                            activeContentColor = OpenWeightsColors.Ink,
                            activeBorderColor = OpenWeightsColors.Lime,
                            inactiveBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                        label = { Text(effort.label, maxLines = 1) },
                    )
                }
            }
        }
    }
}

/**
 * The rest of it, behind one row.
 *
 * A row rather than a second sheet, because these settings are read against the ones above
 * them: a top-p worth changing is one you are changing because of the temperature you just
 * set, and a sheet that replaced this one would hide the number being compared with.
 */
@Composable
private fun AdvancedSettings(
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = Motion.quick(),
        label = "advanced",
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .clickable(onClick = onToggle, role = Role.Button)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Advanced", style = MaterialTheme.typography.titleSmall)
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (open) "Hide advanced settings" else "Show advanced",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(rotation),
            )
        }

        AnimatedVisibility(visible = open) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** Both text fields in this sheet, so the two cannot drift apart. */
@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
)

@Composable
private fun Setting(
    label: String,
    explanation: String,
    value: String,
    footnote: String? = null,
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
        footnote?.let { Metric(it) }
    }
}

private const val MAX_TEMPERATURE = 2f
private const val MIN_TOP_P = 0.1f
private const val MAX_TOP_K = 100f
private const val MIN_REPEAT_PENALTY = 1f
private const val MAX_REPEAT_PENALTY = 1.5f

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
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

/**
 * The widths the slider offers, which end where this model actually runs.
 *
 * A constant ended at 32768, and automatic opens LFM2.5 at 128000, so the sheet read
 * "128000 tokens" beside a thumb pinned at the end of a shorter scale and one touch dropped
 * the window by three quarters with no drag.
 */
internal fun contextRange(loadedContext: Int): ClosedFloatingPointRange<Float> {
    val top = maxOf(loadedContext, ModelLoadParams.MAX_CONTEXT_LENGTH)
    val bottom = ModelLoadParams.MIN_CONTEXT_LENGTH
    return bottom.toFloat()..integralTop(bottom, top).toFloat()
}

/**
 * The top of the scale, moved down until every stop on it is a whole number of tokens.
 *
 * A `Slider` with `steps` snaps to evenly spaced stops, and this one's value is stored as an
 * `Int`, so the value that comes back out has been rounded. When a stop is not a whole
 * number those two disagree: the slider snaps to 4,195.1, the sheet stores 4,195, the slider
 * is handed 4,195 and snaps again, and the thumb sits between two stops jittering between
 * them instead of settling.
 *
 * Whether that happens is pure arithmetic on the range. With [ModelLoadParams.CONTEXT_STEPS]
 * at 30 there are 31 intervals, and 1,024 to 32,768 divides into exactly 1,024 apiece, which
 * is why this was never seen at the default. A model that reports 131,072, which is what most
 * 128K models report, gives 4,195.1 and bounces. LFM2.5's 128,000 gives exactly 4,096 and
 * does not, which is the sort of luck that keeps a bug hidden.
 *
 * Trimming the top costs at most 30 tokens of a range that runs to six figures, and it is the
 * only fix that leaves the slider's own snapping alone.
 */
private fun integralTop(bottom: Int, top: Int): Int {
    val intervals = ModelLoadParams.CONTEXT_STEPS + 1
    val span = (top - bottom) / intervals * intervals
    return if (span <= 0) top else bottom + span
}

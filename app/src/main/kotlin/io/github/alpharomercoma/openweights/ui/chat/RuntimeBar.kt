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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius

/**
 * What is loaded, and what it is doing.
 *
 * Every other chat app can put a product name here, because the thing behind it never
 * changes. Here it changes with every download, and the answer to "why is this slow" is
 * usually in this line: which quantization, which compute device, how much context, and
 * whether the phone has got hot enough to back off.
 *
 * When the runtime is idle the line is the identity; when it is busy the line is the state,
 * because a wait that is named is a wait that can be understood. Reading a prompt with four
 * video frames in it takes a minute on this hardware, and a spinner cannot say that.
 */
@Composable
fun RuntimeBar(
    state: ChatUiState,
    onClick: () -> Unit,
    onResetMode: () -> Unit = {},
    /**
     * Whether a goal or research is currently driving the conversation on its own.
     *
     * Mode is that loop's to set for as long as it runs — planning starts in PLAN
     * regardless of what was on screen before, and a step executes in whatever
     * [goalExecutionMode] chose. A tap landing in either window would hand the mode back
     * to a run that is not expecting it: PLAN would lose the plan it was about to read
     * back, and AUTO on a run started in ASK or YOLO would silently reinstate the checks
     * that mode had waived. The control the reader is tapping is gone by the time the
     * run's own `finally` restores what was there before, so hiding it here rather than
     * merely disabling it is what keeps a well-timed tap from doing anything at all.
     */
    goalRunning: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Read here rather than inside the semantics block, which is not a composition.
    val spoken = stringResource(R.string.choose_a_model_spoken, state.spoken())
    val leaveMode = stringResource(R.string.leave_mode, state.mode.label)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick, role = Role.Button)
            // Merged, or the description is read and then every line inside it is read
            // again: the model name, the backend, the context window, one after another.
            // The whole point of composing a sentence here is that it replaces them.
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .heightIn(min = TOUCH_TARGET.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = state.modelName ?: "Choose a model",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // The chevron is the whole reason anyone discovers that the name is a button.
            // Without it this reads as a label, and switching model becomes a thing you
            // find by tapping the title on the off chance.
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(CHEVRON.dp),
            )
        }

        // What the model is, not what it is doing. There is one place that says work is
        // in flight and it is next to the text being written, where the eye already is;
        // saying it twice made the top bar flicker between an identity and a status while
        // the answer scrolled underneath.
        if (state.runtimeIdentity.isNotEmpty()) {
            Metric(state.runtimeIdentity, maxLines = 1)
        }

        // A mode chosen by typing was otherwise invisible to leave the same way: the label
        // above says which one is on, but nothing said how to stop it being on. Its own
        // touch target, nested inside the bar's, so tapping the mode clears the mode rather
        // than opening the model picker underneath it.
        //
        // The visible text is the mode's own name and nothing else. [AgentMode.label] is a
        // fixed English word chosen by whoever typed `/plan`, the same word this line
        // already showed above before there was anything to tap; wrapping it in a
        // translated sentence only sat the two side by side in every language that is not
        // English. The sentence is said instead, once, to whoever cannot see this is a
        // button.
        if (state.mode != ChatUiState().mode && !goalRunning) {
            Text(
                text = state.mode.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onResetMode, role = Role.Button)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .semantics { contentDescription = leaveMode },
            )
        }
    }
}

/**
 * The bar as one sentence, for a screen reader.
 *
 * Merged: read out as separate nodes this is a model name followed by three
 * unexplained fragments, which is worse than not reading it at all.
 */
private fun ChatUiState.spoken(): String {
    val what = modelName ?: "No model loaded"
    val doing = if (runtimeState.isBusy) runtimeState.label else runtimeIdentity
    return listOf(what, doing).filter { it.isNotEmpty() }.joinToString(", ")
}

/** Android asks interactive targets to be at least this tall. */
private const val TOUCH_TARGET = 48

/** Small enough to sit under the name's cap height rather than beside it. */
private const val CHEVRON = 18

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun RuntimeBarPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RuntimeBar(
                state = ChatUiState(
                    modelName = "LFM2.5-VL-1.6B-Q4_K_M",
                    modelQuantization = "Q4_K_M",
                    backend = "CPU",
                    contextSize = 4096,
                ),
                onClick = {},
            )
            RuntimeBar(
                state = ChatUiState(
                    modelName = "LFM2.5-VL-1.6B-Q4_K_M",
                    modelQuantization = "Q4_K_M",
                    backend = "CPU",
                    contextSize = 4096,
                    isGenerating = true,
                ),
                onClick = {},
            )
        }
    }
}

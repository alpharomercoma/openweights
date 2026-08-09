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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.LocalIsDarkTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsColors
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
fun RuntimeBar(state: ChatUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .combinedClickable(onClick = onClick, role = Role.Button)
            .semantics { contentDescription = "Choose a model. ${state.spoken()}" }
            .heightIn(min = TOUCH_TARGET.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = state.modelName ?: "Choose a model",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val runtime = state.runtimeState
        if (runtime.isBusy) {
            RuntimeStateLine(runtime)
        } else if (state.runtimeIdentity.isNotEmpty()) {
            Metric(state.runtimeIdentity, maxLines = 1)
        }
    }
}

/**
 * The busy line: a dot in the state's own colour, then the state's name.
 *
 * The dot breathes while work is in flight. The one animation in the app tied to something
 * genuinely ongoing rather than to a value that has already settled. Colour is never alone
 * here; the label says the same thing in words.
 */
@Composable
private fun RuntimeStateLine(runtime: RuntimeState) {
    val dark = LocalIsDarkTheme.current
    val color = when (runtime) {
        RuntimeState.THROTTLED ->
            if (dark) OpenWeightsColors.SignalPoor else OpenWeightsColors.PaperSignalPoor

        else -> if (dark) OpenWeightsColors.SignalGood else OpenWeightsColors.PaperSignalGood
    }

    val transition = rememberInfiniteTransition(label = "runtime")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = MIN_PULSE_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 1.dp),
    ) {
        Box(color = color, alpha = pulse)
        Metric(runtime.label, color = color, maxLines = 1)
    }
}

/** The dot. Its own composable only so the alpha applies to it and not to the label. */
@Composable
private fun Box(color: androidx.compose.ui.graphics.Color, alpha: Float) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(DOT_SIZE.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * The bar as one sentence, for a screen reader.
 *
 * Merged deliberately: read out as separate nodes this is a model name followed by three
 * unexplained fragments, which is worse than not reading it at all.
 */
private fun ChatUiState.spoken(): String {
    val what = modelName ?: "No model loaded"
    val doing = if (runtimeState.isBusy) runtimeState.label else runtimeIdentity
    return listOf(what, doing).filter { it.isNotEmpty() }.joinToString(", ")
}

/** Android asks interactive targets to be at least this tall. */
private const val TOUCH_TARGET = 48

/** Slow enough to read as breathing rather than blinking. */
private const val PULSE_MS = 900

private const val MIN_PULSE_ALPHA = 0.25f

private const val DOT_SIZE = 6

@Preview(showBackground = true, backgroundColor = 0xFF0B0D0F)
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

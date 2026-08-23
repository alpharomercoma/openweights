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

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A slider that snaps, without the row of dots that says so.
 *
 * Material draws a tick for every step, and the context length has thirty two of them: the
 * track came out as a dotted rail with a stop indicator at each end, which on a phone reads
 * as a progress bar someone has drawn badly rather than as a control. The snapping is real
 * and worth keeping, because a context window is chosen in powers of two and not in
 * arbitrary token counts; the dots are the only part that had to go.
 *
 * The trailing stop indicator goes with them. Material draws a dot at the end of the track
 * to say where the maximum is, which is what the end of the track already says.
 *
 * Used for every slider in the app, including the ones with no steps at all, because a
 * sheet where one control has a dotted rail and the next does not reads as two different
 * controls doing the same job.
 *
 * @param steps passed to Material unchanged, so the value still snaps to the same points.
 *   Zero for a continuous one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
) {
    // The thumb follows the finger; the caller hears whole numbers.
    //
    // Every caller here stores an Int, so a drag is a loop: the slider proposes a value, the
    // caller rounds it, and the rounded one comes back as the thumb's position. Where the
    // rounding moves the value the thumb is pulled back under the finger on every frame,
    // which is the jitter this exists to stop. Holding the raw position for the length of a
    // drag breaks the loop without changing what anybody is told.
    var raw by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    if (!dragging && raw != value) raw = value

    Slider(
        value = raw,
        onValueChange = {
            dragging = true
            raw = it
            onValueChange(it)
        },
        onValueChangeFinished = {
            dragging = false
            raw = value
        },
        valueRange = valueRange,
        steps = steps,
        modifier = modifier,
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                drawStopIndicator = null,
                drawTick = { _, _ -> },
            )
        },
    )
}

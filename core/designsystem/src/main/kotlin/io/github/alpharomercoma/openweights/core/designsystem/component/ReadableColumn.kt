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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Caps a screen's content to a readable, centered column on wide windows.
 *
 * On every phone this is invisible: the window is narrower than the cap and the modifier
 * changes nothing, which is what makes it safe to put on a screen without re-testing the
 * phone layout. On a tablet it is the difference between a settings row whose label and
 * toggle sit a hand-span apart and one that reads as a row: a 1280dp window otherwise
 * hands every full-width control the whole screen, and controls built for 400dp do not
 * scale, they stretch.
 *
 * The cap is Material's expanded-width pane, roughly the widest a single reading column
 * stays comfortable. Screens with genuinely two-pane ambitions should get a real adaptive
 * layout instead; this is the honest single-column version of that, shipped everywhere
 * the alternative was edge-to-edge stretching.
 */
fun Modifier.readableColumn(max: Dp = READABLE_MAX): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = max)
    .fillMaxWidth()

/** Where a single column stops being a fit and starts being a stretch. */
private val READABLE_MAX: Dp = 720.dp

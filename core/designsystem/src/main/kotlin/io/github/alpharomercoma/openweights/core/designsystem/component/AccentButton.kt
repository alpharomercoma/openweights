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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsColors
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius

/**
 * The one lime thing on a screen: the action you came to take.
 *
 * Its own composable rather than Material's `Button`, and this is the seam where the
 * palette's second rule is kept. Lime is a light colour, so it can be a fill on either
 * canvas and can never be a word or a line on a pale one, where it measures 1.13:1. But
 * Material's `primary` role is painted both ways by different components: a filled button
 * uses it as a container, and a text button, a slider track, a caret and a progress bar all
 * use it as ink. One value cannot satisfy both.
 *
 * So `primary` is the readable one, ink on paper and lime on the dark canvas, which makes
 * every stock component legible in both themes without a single call site overriding
 * anything. Lime as a fill lives here instead, said out loud at the few places that mean
 * it, which is also a fair description of how often a screen should have a primary action.
 *
 * Ink content on lime in both themes, never white, at 12.99:1.
 */
@Composable
fun AccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(Radius.pill),
        contentPadding = contentPadding,
        // Disabled is an outline rather than Material's grey pill. A filled shape in a
        // dimmed neutral is the same shape as a secondary button, so a disabled Save read
        // as a second action rather than as the same action waiting for something; drawn as
        // an outline it is obviously the primary button, obviously not ready.
        border = if (enabled) {
            null
        } else {
            BorderStroke(
                Dp.Hairline,
                MaterialTheme.colorScheme.outline,
            )
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = OpenWeightsColors.Lime,
            contentColor = OpenWeightsColors.Ink,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        content = content,
    )
}

@Preview
@Composable
private fun AccentButtonPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        AccentButton(onClick = {}, modifier = Modifier.padding(16.dp)) { Text("New chat") }
    }
}

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

package io.github.alpharomercoma.openweights.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One corner scale, four steps.
 *
 * The build had drifted to five arbitrary radii, 10, 12, 14, 20, chosen a component at a
 * time, which is exactly how an interface stops looking designed. These four are picked to
 * be distinguishable from each other at arm's length and to sit in the middle the product
 * needs: tighter than the 28 dp-plus that reads as a toy, looser than the 4 dp that reads
 * as a terminal.
 */
object Radius {
    /** Chips, badges, inline code. */
    val xs = 8.dp

    /** Attachment thumbnails, fields inside sheets, small cards. */
    val sm = 12.dp

    /** Cards, list rows, message bubbles, dialogs. The default. */
    val md = 16.dp

    /** The composer and bottom sheets. The two surfaces that own the bottom edge. */
    val lg = 24.dp
}

/** The same scale, as Material's shape roles, so stock components inherit it. */
internal val OpenWeightsShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.lg),
)

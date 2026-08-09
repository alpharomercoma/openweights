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

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * How long anything is allowed to take.
 *
 * Three durations, because a fourth would only invite splitting hairs. The app's job is to
 * get out of the way of a model that already makes people wait: a tab switch that takes as
 * long as a token is a tab switch that feels broken.
 *
 * Compose Navigation defaults to 700 ms fades and Material's own components lean slow.
 * Both are overridden rather than left at defaults.
 */
object Motion {
    // Reduced motion needs no special path here. Compose reads the platform's animator
    // duration scale through MotionDurationScale on the UI dispatcher, so a device with
    // animations turned off completes every one of these immediately. A hand-rolled check
    // would only be a second, less accurate copy of that.

    /** Icon swaps, colour changes, anything the eye should not have time to track. */
    const val INSTANT_MS = 90

    /** Tab switches, list changes, the composer growing a line. The common case. */
    const val QUICK_MS = 160

    /** Sheets, the drawer, dialogs. The only things given room to arrive. */
    const val STANDARD_MS = 220

    /**
     * Material's emphasized curve: a fast start that settles.
     *
     * Used on the way in, where the eye is following something appearing. Exits use a
     * plain fade, because nobody watches a thing leave.
     */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> instant(): FiniteAnimationSpec<T> = tween(INSTANT_MS, easing = Emphasized)

    fun <T> quick(): FiniteAnimationSpec<T> = tween(QUICK_MS, easing = Emphasized)

    fun <T> standard(): FiniteAnimationSpec<T> = tween(STANDARD_MS, easing = Emphasized)
}

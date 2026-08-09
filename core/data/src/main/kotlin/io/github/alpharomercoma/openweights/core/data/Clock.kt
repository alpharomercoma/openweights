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

package io.github.alpharomercoma.openweights.core.data

import java.time.LocalDate
import java.time.ZoneId

/**
 * Time, injectable so tests do not depend on today's date.
 *
 * Days are local, not UTC: a user's "yesterday" is their yesterday, and a chart bucketed
 * by UTC would move activity across midnight for most of the world.
 */
interface Clock {
    fun nowMillis(): Long

    /** Days since the epoch in the device's own time zone. */
    fun today(): Long

    object System : Clock {
        override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
        override fun today(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
    }
}

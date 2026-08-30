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

package io.github.alpharomercoma.openweights.core.common.model

/**
 * Today's date, for the templates that write it into the prompt.

 * Llama 3.2 and SmolLM3 both put the day into their system blocks, each in its own
 * spelling. The month names are English on purpose rather than an oversight:
 * upstream renders with `strftime` in whatever locale the server happens to run, but the
 * prompt the model was trained on is English, and a template is transcribed to feed the
 * model what it expects rather than to be internationalised.
 */
data class PromptDate(val day: Int, val month: Int, val year: Int) {

    /** `26 Jul 2024`, which is Llama 3.2's spelling (`%d %b %Y`, zero-padded day). */
    fun asLlamaDate(): String =
        "${day.toString().padStart(2, '0')} ${SHORT_MONTHS[month - 1]} $year"

    /** `29 August 2026`, which is SmolLM3's spelling (`%d %B %Y`, zero-padded day). */
    fun asSmolLm3Date(): String =
        "${day.toString().padStart(2, '0')} ${LONG_MONTHS[month - 1]} $year"

    private companion object {
        val SHORT_MONTHS = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
        val LONG_MONTHS = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
    }
}

/** The device's own calendar date, spelled per platform. */
expect fun promptDateToday(): PromptDate

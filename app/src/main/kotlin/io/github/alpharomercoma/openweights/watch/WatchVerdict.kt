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

package io.github.alpharomercoma.openweights.watch

/**
 * Whether a check's answer is news, and the answer with the verdict removed.
 *
 * A watch exists to say when something *changed*, and for its first year this one never
 * asked: every completed check notified, so "tell me if the price drops" pinged on every
 * interval including the ones where the model itself wrote "nothing new". The tick prompt
 * now shows the model the previous finding and asks it to end with one word, CHANGED or
 * UNCHANGED, and this is the reader of that word.
 *
 * The reading fails open. A model that forgets the word, or a first check with nothing to
 * compare against, counts as changed: a missed notification about real news is the failure
 * this feature cannot afford, and one extra ping is not. The only silent case earned
 * without the word is an answer byte-identical to the previous one, which is the model
 * repeating itself rather than reporting.
 */
internal object WatchVerdict {
    /** The verdict line, wherever the reply put it on its last non-blank line. */
    private val VERDICT = Regex("""\s*\*{0,2}(CHANGED|UNCHANGED)\*{0,2}[.!]?\s*$""")

    data class Read(val summary: String, val changed: Boolean)

    fun read(reply: String, previous: String?): Read {
        val trimmed = reply.trim()
        val match = VERDICT.find(trimmed)
        val summary = (match?.let { trimmed.removeRange(it.range) } ?: trimmed)
            .trim()
            .ifBlank { "Nothing new." }
        val changed = when {
            previous == null -> true
            match != null -> match.groupValues[1] == "CHANGED"
            else -> summary.trim() != previous.trim()
        }
        return Read(summary, changed)
    }
}

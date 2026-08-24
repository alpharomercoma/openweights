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
 * How long an answer should be, as a preference the model reads.
 *
 * **Not a token cap, and that is the whole design.** A cap is the obvious way to build a
 * length control and it is the wrong one here: the sampler stops mid-sentence, and a reply
 * cut off mid-thought is exactly the failure that left a turn in the history no longer
 * matching the KV cache and cost every later turn a full re-prefill. A cap would also make
 * the answer worse in the one way nobody wants, by removing the end of it.
 *
 * A preference asks the model to plan a shorter answer instead of writing a long one and
 * losing the tail. Measured on three wordings against six questions split between ones that
 * want brevity and ones that want detail:
 *
 * | Model | | Simple | Medium | Long |
 * | --- | --- | ---: | ---: | ---: |
 * | 2.6B | Brief | 19 chars | 952 | 1,478 |
 * | 2.6B | Balanced | 17 | 1,273 | 2,392 |
 * | 2.6B | Thorough | 102 | 2,488 | **3,589** |
 * | 1.2B | Brief | 33 | 372 | 392 |
 * | 1.2B | Balanced | 150 | 392 | 855 |
 * | 1.2B | Thorough | 40 | 401 | **1,044** |
 *
 * Long answers separate by a factor of two and a half on the larger model and two and a half
 * on the smaller, which is a control worth having. Simple questions stay short under Brief
 * and Balanced and grow under Thorough, which is the one place the setting does something a
 * reader might not want and is why Thorough is not the default.
 *
 * There is deliberately no second control for tables. The same setting moves them: the same
 * comparison question produced a seven row table under Brief and a twenty one row one under
 * Thorough, so a separate slider would be a second way to say what this already says.
 */
enum class AnswerLength(val label: String, val description: String) {
    BRIEF("Brief", "The fewest sentences that answer the question"),
    BALANCED("Balanced", "As long as the question calls for"),
    THOROUGH("Thorough", "Cover what you would ask next, with sections"),
    ;

    /** The sentence that goes into the system message. */
    val instruction: String
        get() = when (this) {
            BRIEF ->
                "Answer from what you know. Reply with the answer itself and keep it short: " +
                    "the fewest sentences that answer the question."

            BALANCED ->
                "Answer from what you know. Reply with the answer itself, at the length the " +
                    "question calls for: a sentence for a simple one, and as long as it " +
                    "takes for one that asks for detail."

            THOROUGH ->
                "Answer from what you know. Reply with the answer itself, and be thorough: " +
                    "cover the parts of the question somebody would ask about next, and use " +
                    "sections when there is more than one."
        }

    companion object {
        fun fromName(name: String?): AnswerLength =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

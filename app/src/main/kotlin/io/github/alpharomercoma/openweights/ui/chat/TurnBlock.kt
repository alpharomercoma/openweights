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

import io.github.alpharomercoma.openweights.core.tools.AgentStep

/**
 * What happened during a turn, in the order it happened.
 *
 * A turn with tools in it is not one block of text. The model says something, calls
 * something, reads what came back, says something else. The first version kept only the
 * steps and the final answer and threw the rest away, so a reply that had reasoned its way
 * from "this looks like a personal site" to "so fetch that one" arrived as four tool chips
 * and a conclusion, with the part that explained the middle missing.
 *
 * Keeping it ordered rather than grouping prose and steps separately is the point: what
 * makes an agent readable is seeing why each call followed the last.
 */
sealed interface TurnBlock {
    /**
     * Something the model said on the way to the answer.
     *
     * Its own type rather than being folded into the final text, because it was written
     * before a tool ran and reads as stale once the answer contradicts it.
     */
    data class Said(val text: String) : TurnBlock

    /** A tool it asked for, ran, or was refused. */
    data class Step(val step: AgentStep) : TurnBlock
}

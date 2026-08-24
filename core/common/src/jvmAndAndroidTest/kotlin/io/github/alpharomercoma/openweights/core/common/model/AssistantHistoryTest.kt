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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one case that cost eleven to nineteen seconds a turn.
 *
 * A reply cut off by the token limit while it was still thinking has no closing tag, which
 * makes it indistinguishable from a reply that never thought. Inferring from the text put
 * the opening tag back on one and not the other, and the one that missed out sat in the
 * history no longer matching the KV cache. On a hybrid model, which cannot roll back part
 * of a recurrent state, that costs a full re-prefill of the conversation on every turn
 * after it: measured at 1,393 to 1,931 prompt tokens where 20 to 45 were new.
 */
class AssistantHistoryTest {
    @Test
    fun `a reply cut off mid-thought still gets its opening tag back`() {
        val truncated = "The user is asking about tomatoes. I should check whether"

        assertThat(assistantHistoryText(truncated, thinkingPrefilled = true))
            .isEqualTo("<think>$truncated")
        // Which the text alone cannot tell you, and this is the bug being pinned.
        assertThat(assistantHistoryText(truncated)).isEqualTo(truncated)
    }

    @Test
    fun `a reply that finished thinking is unchanged either way`() {
        val finished = "Thought about it</think>Tomatoes need eight hours."

        assertThat(assistantHistoryText(finished, thinkingPrefilled = true))
            .isEqualTo("<think>$finished")
        assertThat(assistantHistoryText(finished)).isEqualTo("<think>$finished")
    }

    @Test
    fun `a tag the model wrote itself is not doubled`() {
        val ownTag = "<think>Mine</think>Answer."

        assertThat(assistantHistoryText(ownTag, thinkingPrefilled = true)).isEqualTo(ownTag)
        assertThat(assistantHistoryText(ownTag)).isEqualTo(ownTag)
    }

    @Test
    fun `a model whose template opens nothing is left alone`() {
        val plain = "Tomatoes need eight hours of sun."

        assertThat(assistantHistoryText(plain, thinkingPrefilled = false)).isEqualTo(plain)
        assertThat(assistantHistoryText(plain)).isEqualTo(plain)
    }
}

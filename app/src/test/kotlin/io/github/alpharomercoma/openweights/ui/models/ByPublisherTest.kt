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

package io.github.alpharomercoma.openweights.ui.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class ByPublisherTest {
    private fun model(name: String, publisher: String?) =
        LocalModel(File("/models/$name.gguf"), null, publisher)

    @Test
    fun `publishers are alphabetical and unattributed files go last`() {
        val groups = listOf(
            model("Qwen3-1.7B", "Qwen"),
            model("something-i-built", null),
            model("LFM2.5-2.6B", "LiquidAI"),
            model("Qwen3-0.6B", "Qwen"),
            model("LFM2.5-1.2B", "LiquidAI"),
        ).byPublisher()

        assertThat(groups.map { it.heading })
            .containsExactly("LiquidAI", "Qwen", "Added by hand").inOrder()
        assertThat(groups.first().models.map { it.name })
            .containsExactly("LFM2.5-1.2B", "LFM2.5-2.6B").inOrder()
    }

    @Test
    fun `download order does not survive into the list`() {
        val first = model("Zephyr", "AAA")
        val second = model("Alpha", "ZZZ")

        assertThat(listOf(second, first).byPublisher().map { it.heading })
            .containsExactly("AAA", "ZZZ").inOrder()
    }

    @Test
    fun `a phone with nothing installed groups into nothing`() {
        assertThat(emptyList<LocalModel>().byPublisher()).isEmpty()
    }
}

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
    fun `publishers are alphabetical and unattributed files go last with no heading`() {
        val groups = listOf(
            model("Qwen3-1.7B", "Qwen"),
            model("something-i-built", null),
            model("LFM2.5-2.6B", "LiquidAI"),
            model("Qwen3-0.6B", "Qwen"),
            model("LFM2.5-1.2B", "LiquidAI"),
        ).byPublisher()

        assertThat(groups.map { it.heading })
            .containsExactly("LiquidAI", "Qwen", null).inOrder()
        assertThat(groups.first().models.map { it.name })
            .containsExactly("LFM2.5-1.2B", "LFM2.5-2.6B").inOrder()
    }

    @Test
    fun `a model with no recorded publisher is read off its family name`() {
        // Every install that predates the app recording publishers has none of them, which
        // is what put "Added by hand" over every model on somebody's phone.
        val groups = listOf(
            model("LFM2.5-2.6B-QAD-Q4_0", null),
            model("Qwen3-1.7B-Instruct", null),
            model("gemma-3-4b-it", null),
        ).byPublisher()

        assertThat(groups.map { it.heading })
            .containsExactly("Google", "LiquidAI", "Qwen").inOrder()
    }

    @Test
    fun `a name nobody can be read off gets no heading rather than a guess`() {
        val groups = listOf(
            model("my-qwen-experiment", null),
            model("finetune-v3", null),
        ).byPublisher()

        assertThat(groups).hasSize(1)
        assertThat(groups.single().heading).isNull()
        assertThat(groups.single().models).hasSize(2)
    }

    @Test
    fun `a recorded publisher beats the filename`() {
        // Somebody's fork of a Qwen model, downloaded from their repository, belongs to them.
        val groups = listOf(model("Qwen3-1.7B-mine", "alpharomercoma")).byPublisher()

        assertThat(groups.single().heading).isEqualTo("alpharomercoma")
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

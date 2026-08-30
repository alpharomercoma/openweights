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

package io.github.alpharomercoma.openweights.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownWideTableTest {
    @Test
    fun `many short columns are wide`() {
        val markdown = """
            A | B | C | D | E | F
            --- | --- | --- | --- | --- | ---
            1 | 2 | 3 | 4 | 5 | 6
        """.trimIndent()

        assertThat(markdown.wideMarkdownTableChars()).isGreaterThan(0)
    }

    @Test
    fun `optional outer pipes and long data cells are detected`() {
        val markdown = """
            Name | Explanation
            --- | ---
            Runtime | This value is substantially longer than a compact phone can show
        """.trimIndent()

        assertThat(markdown.wideMarkdownTableChars()).isGreaterThan(0)
    }

    @Test
    fun `small two column table does not make the whole reply scroll`() {
        val markdown = """
            Name | Value
            --- | ---
            CPU | 4
        """.trimIndent()

        assertThat(markdown.wideMarkdownTableChars()).isEqualTo(0)
    }
}

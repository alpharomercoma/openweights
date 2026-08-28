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

package io.github.alpharomercoma.openweights.core.tools

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import org.junit.Test

class ToolRegistryTest {
    private val media = object : Tool {
        override val definition = ToolDefinition(SearchMediaTool.NAME, "Shows pictures", "{}")
        override val returnsUntrustedText = true
        override suspend fun run(call: ToolCall): String = ""
    }

    /**
     * The rename that motivated this: `search_media` became `show_pictures` after a routing
     * measurement, but every step already stored in conversations still names the old tool.
     * A reopened conversation rebuilds its notes by looking each stored step's tool up
     * again, and a name that stopped resolving lost `returnsUntrustedText` — text a
     * stranger wrote re-entered the prompt marked as trusted.
     */
    @Test
    fun `a step stored under the tool's old name still resolves to the tool`() {
        val registry = ToolRegistry(listOf(media))

        assertThat(registry.find(SearchMediaTool.LEGACY_NAME)).isSameInstanceAs(media)
        assertThat(registry.find(SearchMediaTool.NAME)).isSameInstanceAs(media)
        assertThat(registry.find("never_existed")).isNull()
    }
}

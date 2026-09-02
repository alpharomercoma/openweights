/*
 * Copyright 2026 The OpenWeights Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
import org.junit.Test

class ResearchEvidenceTest {
    private val source = "https://example.test/article"

    @Test
    fun `a successful search followed by reading one of its sources is evidence`() {
        val final = "https://www.example.test/article"
        val steps = listOf(
            ran(
                "web_search",
                evidence = ToolEvidence.Search(setOf(source)),
            ),
            ran(
                "fetch_url",
                evidence = ToolEvidence.Fetch(source, final),
            ),
        )

        assertThat(steps.correlatedWebResearchSources()).containsExactly(final)
    }

    @Test
    fun `search alone is not evidence that a source was read`() {
        val steps = listOf(ran("web_search", evidence = ToolEvidence.Search(setOf(source))))

        assertThat(steps.correlatedWebResearchSources()).isEmpty()
    }

    @Test
    fun `fetching an address the search did not return is rejected`() {
        val steps = listOf(
            ran("web_search", evidence = ToolEvidence.Search(setOf(source))),
            ran(
                "fetch_url",
                evidence = ToolEvidence.Fetch(
                    "https://other.test/claim",
                    "https://other.test/claim",
                ),
            ),
        )

        assertThat(steps.correlatedWebResearchSources()).isEmpty()
    }

    @Test
    fun `an unsuccessful typed fetch cannot satisfy research`() {
        val steps = listOf(
            ran("web_search", evidence = ToolEvidence.Search(setOf(source))),
            ran(
                "fetch_url",
                successful = false,
                evidence = ToolEvidence.Fetch(source, source),
            ),
        )

        assertThat(steps.correlatedWebResearchSources()).isEmpty()
    }

    @Test
    fun `convincing prose without typed evidence cannot satisfy research`() {
        val steps = listOf(
            ran("web_search", result = "Results for topic\n$source"),
            ran("fetch_url", result = "The sourced article."),
        )

        assertThat(steps.correlatedWebResearchSources()).isEmpty()
    }

    private fun ran(
        name: String,
        result: String = "done",
        successful: Boolean = true,
        evidence: ToolEvidence? = null,
    ): AgentStep.Ran = AgentStep.Ran(
        call = ToolCall(id = name, name = name, argumentsJson = "{}"),
        result = result,
        millis = 1,
        successful = successful,
        evidence = evidence,
    )
}

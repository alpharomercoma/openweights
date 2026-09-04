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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The text a report turns into, which is now the whole of what a report is.
 *
 * Nothing stores one any more, so this string is the entire artefact: whatever the reader
 * pastes into a mail, an issue or their own notes is exactly what comes out of here.
 */
class ShareReportTest {
    @Test
    fun `a report names the model, the reason and the reply`() {
        val text = reportText(
            modelName = "Hammer2.1-1.5B-Q4_0",
            reason = ReportReason.WRONG,
            replyText = "The moon is made of cheese.",
            note = "it invented a quote",
        )

        assertThat(text).isEqualTo(
            """
            Model: Hammer2.1-1.5B-Q4_0
            Reason: Confidently wrong
            Note: it invented a quote

            Reply:
            The moon is made of cheese.
            """.trimIndent(),
        )
    }

    @Test
    fun `a blank note leaves no heading behind`() {
        // An empty "Note:" line says somebody declined to write one, which is not something
        // the person reading the report needs told.
        val text = reportText(
            modelName = "qwen",
            reason = ReportReason.OFFENSIVE,
            replyText = "something",
            note = "   ",
        )

        assertThat(text).doesNotContain("Note:")
    }

    @Test
    fun `an empty reply says so rather than trailing off`() {
        val text = reportText(
            modelName = "qwen",
            reason = ReportReason.OTHER,
            replyText = "",
            note = "",
        )

        assertThat(text).endsWith("(an empty reply)")
    }

    @Test
    fun `the subject names the model, so two reports can be told apart`() {
        assertThat(reportSubject("qwen")).isEqualTo("OpenWeights report: qwen")
    }
}

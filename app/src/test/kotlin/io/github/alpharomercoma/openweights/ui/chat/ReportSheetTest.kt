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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.alpharomercoma.openweights.core.data.ReportReason
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The control the app is required to have.
 *
 * Google's generative AI policy asks for in-app reporting or flagging of offensive output,
 * reachable without leaving the app, and this is it. That makes a regression here different
 * in kind from a regression anywhere else on this screen: the app would still work, and it
 * would be out of policy, which is not a state anything else in the suite would notice.
 *
 * `docs/store-listing.md` describes this control to the reviewer in the words below — a
 * reason, an optional note, and the report shown in full before it is filed — so these
 * assertions are also the ones keeping that paragraph true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class ReportSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a reason can be chosen and reaches the caller`() {
        var filed: Pair<ReportReason, String>? = null
        showSheet(onSubmit = { reason, note -> filed = reason to note })

        compose.onNodeWithText("Dangerous or illegal advice").performClick()
        compose.onNodeWithText("Report").performClick()

        assert(filed?.first == ReportReason.DANGEROUS) {
            "expected the chosen reason to be filed, got ${filed?.first}"
        }
    }

    @Test
    fun `the note is optional and travels with the reason`() {
        var filed: Pair<ReportReason, String>? = null
        showSheet(onSubmit = { reason, note -> filed = reason to note })

        compose.onNodeWithText("Offensive or hateful").performClick()
        compose.onNodeWithText("Anything to add", substring = true)
            .performTextInput("it invented a quote")
        compose.onNodeWithText("Report").performClick()

        assert(filed?.second == "it invented a quote") {
            "expected the note to be filed with the reason, got ${filed?.second}"
        }
    }

    @Test
    fun `nothing is filed until a reason is picked`() {
        // A report with no reason is a row nobody can act on, and the sheet is the only
        // thing standing between one and the database.
        var filed = false
        showSheet(onSubmit = { _, _ -> filed = true })

        compose.onNodeWithText("Report").performClick()

        assert(!filed) { "a report with no reason must not be filed" }
    }

    @Test
    fun `the report says what it is about before it is sent`() {
        // Named in the listing as part of the control: the model and the reply are shown, so
        // filing a report is never a blind action on text the user cannot see.
        showSheet()

        compose.onNodeWithText("Hammer2.1-1.5B-Q4_0", substring = true).assertIsDisplayed()
        compose.onNodeWithText("The moon is made of cheese.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `every reason the repository knows about is offered`() {
        // Enumerated rather than spot checked. A reason that exists in ReportReason and not
        // on this sheet is unreachable, and the one most likely to be added later is the one
        // a policy change asks for.
        showSheet()

        ReportReason.entries.forEach { reason ->
            compose.onNodeWithText(reason.label).assertIsDisplayed()
        }
    }

    private fun showSheet(onSubmit: (ReportReason, String) -> Unit = { _, _ -> }) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ReportSheet(
                    modelName = "Hammer2.1-1.5B-Q4_0",
                    replyText = "The moon is made of cheese.",
                    onSubmit = onSubmit,
                    onDismiss = {},
                )
            }
        }
    }
}

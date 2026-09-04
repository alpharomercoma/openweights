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
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ReplyConfidence
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ln

/**
 * What the uncertainty view says, and the two things it must never say.
 *
 * It must never present nothing measured as a model that was certain: those look identical
 * on screen and mean opposite things, and confusing them is the failure this whole feature
 * exists to avoid making. And it must never let the numbers stand without the sentence that
 * says what they are not, because a confident invention is exactly what somebody reading
 * underlines will assume has been caught for them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class UncertaintySheetTest {
    @get:Rule
    val compose = createComposeRule()

    private fun logOf(probability: Double) = ln(probability).toFloat()

    @Test
    fun `the answer is shown whole, hesitations and all`() {
        showSheet(
            texts = listOf("The capital is ", "Vaduz", "."),
            logprobs = listOf(0f, logOf(0.06), 0f),
        )

        // Every run put back together is the answer. A view that drew only the marked
        // words would be quoting the model out of context, which is the one thing a tool
        // for checking an answer cannot do.
        compose.onNodeWithText("The capital is Vaduz.").assertIsDisplayed()
    }

    @Test
    fun `a reply the model was sure of says so rather than showing an empty page`() {
        showSheet(texts = listOf("Two", " plus", " two", " is", " four"), logprobs = List(5) { 0f })

        compose.onNodeWithText("sure of every word", substring = true).assertIsDisplayed()
    }

    @Test
    fun `nothing measured is said out loud, not shown as certainty`() {
        showSheet(texts = emptyList(), logprobs = emptyList())

        compose.onNodeWithText("Nothing was measured", substring = true).assertIsDisplayed()
        // The counterweight. An unmeasured reply must not borrow the sentence a certain one
        // gets, because the two are opposite claims.
        compose.onNodeWithText("sure of every word", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the caveat is on the screen with the numbers`() {
        // Not in a comment, not in the docs. The person who most needs to be told that a
        // low perplexity is not a truth check is the one reading the underlines.
        showSheet(
            texts = listOf("It was ", "1847", "."),
            logprobs = listOf(0f, logOf(0.03), 0f),
        )

        compose.onNodeWithText("not where it was wrong", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Perplexity", substring = true).assertIsDisplayed()
    }

    @Test
    fun `how many places were marked is said before the answer`() {
        showSheet(
            texts = listOf("a ", "zzz", " b ", "qqq"),
            logprobs = listOf(0f, logOf(0.01), 0f, logOf(0.02)),
        )

        // Two marked runs out of four, at the shipped threshold of twenty percent.
        compose.onNodeWithText("2 of 4", substring = true).assertIsDisplayed()
        compose.onNodeWithText("20%", substring = true).assertIsDisplayed()
    }

    private fun showSheet(texts: List<String>, logprobs: List<Float>) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                UncertaintySheet(
                    entry = TranscriptEntry(
                        id = 1,
                        role = ChatRole.ASSISTANT,
                        text = texts.joinToString(""),
                        confidence = ReplyConfidence.of(texts, logprobs),
                    ),
                    onDismiss = {},
                )
            }
        }
    }
}

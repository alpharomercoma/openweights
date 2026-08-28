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

package io.github.alpharomercoma.openweights.ui.discover

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.alpharomercoma.openweights.core.common.model.GgufFileType
import io.github.alpharomercoma.openweights.core.common.model.GgufMetadata
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.device.FitReport
import io.github.alpharomercoma.openweights.core.device.FitVerdict
import io.github.alpharomercoma.openweights.core.hub.HubFile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sentence somebody reads before spending a gigabyte of mobile data.
 *
 * `FitEstimator`'s own comment says this is the product: every on-device app can list
 * models, and the useful thing is saying "this one will not load" before the download rather
 * than after it. All of that work arrives here as one line, and the line is the only part
 * the user sees, so a verdict that renders as the wrong sentence undoes the estimator
 * entirely while looking perfectly healthy.
 *
 * The three verdicts are asserted separately because they are three different promises, and
 * the one that matters most is the refusal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class FitCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a model that will not run says so rather than offering a download`() {
        showFit(FitVerdict.WONT_RUN)

        compose.onNodeWithText("Will not run at this context length").assertIsDisplayed()
    }

    @Test
    fun `a tight fit warns that other apps may be closed`() {
        // The middle verdict is the one worth having: comfortable and impossible are both
        // easy calls, and "it will run and cost you your other apps" is the one a user
        // cannot work out from a file size.
        showFit(FitVerdict.TIGHT)

        compose.onNodeWithText("Runs, but tight", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a comfortable fit says so and offers the download`() {
        showFit(FitVerdict.COMFORTABLE)

        compose.onNodeWithText("Runs comfortably").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsDisplayed()
    }

    @Test
    fun `no room to download is a different refusal from will not run`() {
        // Storage and memory fail for different reasons and have different remedies, and
        // collapsing them into one message tells somebody to buy a new phone when they
        // could have deleted a video.
        showFit(FitVerdict.NO_ROOM_TO_DOWNLOAD)

        compose.onNodeWithText("Not enough free storage to download").assertIsDisplayed()
    }

    @Test
    fun `a throughput estimate is only shown when there is one`() {
        // The estimator reports a rate only when it has a real measurement to extrapolate
        // from, so the card must not invent one. A card that printed "~0 tok/s" for an
        // unknown rate would be a promise nobody made.
        showFit(FitVerdict.COMFORTABLE, tokensPerSecond = null)

        compose.onNodeWithText("tok/s", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a prefill estimate is shown alongside decode, not in place of it`() {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                FitCard(
                    inspected = InspectedFile(
                        file = HubFile(
                            path = "LFM2.5-2.6B-Q4_K_M.gguf",
                            sizeBytes = 1_600_000_000L,
                            sha256 = null,
                        ),
                        metadata = GgufMetadata(
                            architecture = "lfm2",
                            blockCount = 30,
                            embeddingLength = 2048,
                            headCount = 32,
                            keyValueHeadsPerLayer = List(30) { 8 },
                            trainingContextLength = 32_768,
                            fileType = GgufFileType.Q4_K_M,
                            name = "LFM2.5-2.6B",
                        ),
                        fit = FitReport(
                            verdict = FitVerdict.COMFORTABLE,
                            requiredMemoryBytes = 2_100_000_000L,
                            usableMemoryBytes = 4_000_000_000L,
                            kvCacheBytes = 240_000_000L,
                            estimatedDecodeTokensPerSecond = 13.8,
                            estimatedPrefillTokensPerSecond = 70.0,
                            maxContextLength = 8_192,
                        ),
                    ),
                    onDownload = {},
                )
            }
        }

        compose.onNodeWithText("14 tok/s decode", substring = true).assertIsDisplayed()
        compose.onNodeWithText("70 tok/s prefill", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an architecture this build cannot load withholds the download`() {
        // The reason the submodule moved: Ling 3.0 is bailingmoe3, which llama.cpp learned
        // after the previous pin was cut. An install that predates it can still find the
        // model, so the card has to refuse before several gigabytes rather than let the
        // load fail afterwards, and it has to say which way out there is.
        showFit(FitVerdict.COMFORTABLE, unsupportedArchitecture = "bailingmoe3")

        compose.onNodeWithText("cannot load bailingmoe3", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test
    fun `an unloadable file does not also claim to run comfortably`() {
        // Memory is not the binding constraint when the engine cannot read the format, and
        // a card carrying both sentences is a card arguing with itself.
        showFit(FitVerdict.COMFORTABLE, unsupportedArchitecture = "bailingmoe3")

        compose.onNodeWithText("Runs comfortably").assertDoesNotExist()
    }

    private fun showFit(
        verdict: FitVerdict,
        tokensPerSecond: Double? = 13.8,
        unsupportedArchitecture: String? = null,
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                FitCard(
                    inspected = InspectedFile(
                        file = HubFile(
                            path = "LFM2.5-2.6B-Q4_K_M.gguf",
                            sizeBytes = 1_600_000_000L,
                            sha256 = null,
                        ),
                        metadata = GgufMetadata(
                            architecture = "lfm2",
                            blockCount = 30,
                            embeddingLength = 2048,
                            headCount = 32,
                            keyValueHeadsPerLayer = List(30) { 8 },
                            trainingContextLength = 32_768,
                            fileType = GgufFileType.Q4_K_M,
                            name = "LFM2.5-2.6B",
                        ),
                        unsupportedArchitecture = unsupportedArchitecture,
                        fit = FitReport(
                            verdict = verdict,
                            requiredMemoryBytes = 2_100_000_000L,
                            usableMemoryBytes = 4_000_000_000L,
                            kvCacheBytes = 240_000_000L,
                            estimatedDecodeTokensPerSecond = tokensPerSecond,
                            maxContextLength = 8_192,
                        ),
                    ),
                    onDownload = {},
                )
            }
        }
    }
}

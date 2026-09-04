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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.ui.models.ActiveDownload
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stopping a download from the place it is being watched.
 *
 * A download in flight appears in three places and could be stopped in one of them. This
 * sheet is where somebody watches it: it opens over the conversation the model is being
 * fetched for, and the only way out was two taps away behind a row labelled "Manage
 * models", which is not what a person who has just changed their mind goes looking for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class ModelPickerSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val download = ActiveDownload(
        repoId = "LiquidAI/LFM2.5-VL-3B-GGUF",
        path = "LFM2.5-VL-3B-Q4_0.gguf",
        key = "LFM2.5-VL-3B-Q4_0.gguf",
        bytesDone = 400_000_000,
        bytesTotal = 1_600_000_000,
    )

    @Test
    fun `a download being watched can be stopped where it is being watched`() {
        var cancelled: String? = null
        showPicker(onCancelDownload = { cancelled = it })

        compose.onNodeWithContentDescription("Cancel download").performClick()

        assert(cancelled == download.key) { "the cancel must name the download, was $cancelled" }
    }

    @Test
    fun `the download is still named and measured beside the way out`() {
        // The stop must not cost the row what it was for. A file name and a byte count are
        // how somebody tells which of two downloads they are about to abandon.
        showPicker()

        compose.onNodeWithText("LFM2.5-VL-3B-Q4_0.gguf").assertIsDisplayed()
        compose.onNodeWithText("of", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a sheet with nothing downloading offers nothing to stop`() {
        showPicker(downloads = emptyList())

        compose.onNodeWithContentDescription("Cancel download").assertDoesNotExist()
    }

    private fun showPicker(
        downloads: List<ActiveDownload> = listOf(download),
        onCancelDownload: (String) -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ModelPickerSheet(
                    models = emptyList(),
                    downloads = downloads,
                    activeName = null,
                    onSelect = {},
                    onCancelDownload = onCancelDownload,
                    onBrowse = {},
                    onManage = {},
                    onDismiss = {},
                )
            }
        }
    }
}

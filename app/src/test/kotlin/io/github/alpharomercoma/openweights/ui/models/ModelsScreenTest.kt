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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Where gigabytes are listed and got rid of.
 *
 * A model is the largest thing this app will ever put on a phone, and the privacy policy
 * makes deleting one a stated remedy, so what is worth asserting is that a delete reaches
 * the store and that a download in flight can be stopped rather than waited out. The rest of
 * the screen is a list.
 *
 * Real files in a temporary directory rather than mocks, because `LocalModel` reads
 * `file.length()` for the size it shows and a model of zero bytes would render a row nobody
 * ever sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class ModelsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val folder: File = Files.createTempDirectory("openweights-models").toFile()

    @Test
    fun `an installed model is listed by name`() {
        showModels(models = listOf(model("LFM2.5-2.6B-Q4_K_M.gguf")))

        compose.onNodeWithText("LFM2.5-2.6B-Q4_K_M", substring = true).assertIsDisplayed()
    }

    @Test
    fun `deleting a model reaches the store`() {
        // The policy says deleting a model removes its weights, and this is the control that
        // promise reduces to.
        var deleted: LocalModel? = null
        showModels(models = listOf(model("LFM2.5-2.6B-Q4_K_M.gguf")), onDelete = { deleted = it })

        compose.onNodeWithText("Delete").performClick()

        assert(deleted?.name == "LFM2.5-2.6B-Q4_K_M") {
            "expected the model to be deleted, got ${deleted?.name}"
        }
    }

    @Test
    fun `a download in flight can be stopped`() {
        // A model is the largest thing this app downloads, and a download that can only be
        // waited out is a phone's data allowance spent by a mistap.
        var cancelled: String? = null
        showModels(
            downloads = listOf(
                ActiveDownload(
                    repoId = "LiquidAI/LFM2.5-2.6B-GGUF",
                    path = "LFM2.5-2.6B-Q4_K_M.gguf",
                    key = "LFM2.5-2.6B-Q4_K_M.gguf",
                    bytesDone = 400_000_000,
                    bytesTotal = 1_600_000_000,
                ),
            ),
            onCancelDownload = { cancelled = it },
        )

        compose.onNodeWithText("Cancel").performClick()

        assert(cancelled == "LFM2.5-2.6B-Q4_K_M.gguf") {
            "expected the download key to be cancelled, got $cancelled"
        }
    }

    @Test
    fun `a download being checked says so rather than looking stalled`() {
        // Verification reads the whole file back and produces no progress while it does, so
        // a bar sitting at a hundred per cent with nothing happening is what this avoids.
        showModels(
            downloads = listOf(
                ActiveDownload(
                    repoId = "LiquidAI/LFM2.5-2.6B-GGUF",
                    path = "LFM2.5-2.6B-Q4_K_M.gguf",
                    key = "LFM2.5-2.6B-Q4_K_M.gguf",
                    bytesDone = 1_600_000_000,
                    bytesTotal = 1_600_000_000,
                    isVerifying = true,
                ),
            ),
        )

        compose.onNodeWithText("Verifying checksum", substring = true).assertIsDisplayed()
    }

    private fun model(name: String): LocalModel {
        val file = File(folder, name).apply { writeBytes(ByteArray(1024)) }
        return LocalModel(file = file)
    }

    private fun showModels(
        models: List<LocalModel> = emptyList(),
        downloads: List<ActiveDownload> = emptyList(),
        onDelete: (LocalModel) -> Unit = {},
        onCancelDownload: (String) -> Unit = {},
    ) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ModelsScreen(
                    state = ModelsUiState(models = models, downloads = downloads),
                    onUse = {},
                    onDelete = onDelete,
                    onCancelDownload = onCancelDownload,
                )
            }
        }
    }
}

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

import android.net.Uri
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.engine.MediaSupport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The file the camera was pointed at, across the recreation that happens behind it.
 *
 * Taking a photograph puts another app in front of this one, and an activity in the
 * background is recreated whenever the system feels like it. The sheet recomposes then, and
 * a plain `remember` minted a second capture URI, which the result that arrived afterwards
 * named instead of the one the camera had actually written into. The photograph was gone,
 * and nothing on screen said so.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureUriSurvivalTest {
    @get:Rule
    val compose = createComposeRule()

    private val restoration = StateRestorationTester(compose)

    @Test
    fun `the capture the camera was given survives the activity being recreated`() {
        val minted = mutableListOf<Uri>()

        restoration.setContent {
            OpenWeightsTheme {
                AttachmentSheet(
                    support = MediaSupport(vision = true),
                    newCaptureUri = {
                        Uri.parse("content://test/capture-${minted.size}").also(minted::add)
                    },
                    onPicked = {},
                    onPickedAll = {},
                    onPickedDocument = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        assertThat(minted).hasSize(1)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        // One, not two. A second would be a different file, and creating it is what used to
        // delete the one being written into.
        assertThat(minted).hasSize(1)
    }
}

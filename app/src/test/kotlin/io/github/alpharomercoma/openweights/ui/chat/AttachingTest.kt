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

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.model.AttachmentStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A document the provider will not hand over is a sentence for the user, not a crash.
 *
 * The picked-file path has caught that since the first revoked clipboard URI; the document
 * path did not, and an exception out of it left the composer's own scope with nothing above
 * it to catch it. The same fake provider is the one thing this test needs, so it is a
 * subclass rather than a Robolectric content provider: the real store swallows every
 * refusal into null before [Staging] sees it, and the throw has to happen after.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AttachingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a document the provider refuses is reported, not thrown`() = runTest {
        val refusing = object : Staging(AttachmentStore(context), context) {
            override suspend fun document(uri: Uri, budgetChars: Int): Staged =
                throw SecurityException("Permission Denial: reading the document")
        }
        val state = MutableStateFlow(ChatUiState(modelName = "model-a"))
        // The test's own scope rather than its background one, so the throw this guards
        // against would fail the test here rather than be reported after it.
        val attaching = Attaching(
            staging = refusing,
            scope = this,
            state = state,
            limitMessage = "too many",
            unreadableMessage = "That file could not be read.",
            loadingMessage = "still loading",
        )

        attaching.stageDocument(Uri.parse("content://documents/refused.txt"))
        advanceUntilIdle()

        assertThat(state.value.error).isEqualTo("That file could not be read.")
        assertThat(state.value.stagedDocument).isNull()
        // And the paperclip is not left spinning over a copy that will never finish.
        assertThat(state.value.isAttaching).isFalse()
    }
}

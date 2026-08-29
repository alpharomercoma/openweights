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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A picture pasted into the composer, on a device, through the whole platform path.
 *
 * On a device rather than under Robolectric, and not by choice: a paste that carries
 * anything but text is handed to the field by the platform clipboard through Compose's
 * receive-content plumbing, and the host-side clipboard has no such thing. Under Robolectric
 * the same actions paste the words and drop the picture, which is precisely the bug this is
 * here to catch — so a green host run would prove nothing at all.
 *
 * The picture is a real file behind the app's own provider, so the URI resolves to
 * `image/png` the way a screenshot from the gallery does, rather than by a stub that would
 * agree with whatever the composer happened to ask.
 */
@RunWith(AndroidJUnit4::class)
class PasteImageOnDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService<ClipboardManager>()!!

    @Test
    fun aPastedPictureIsStagedRatherThanIgnored() {
        val picture = provided("pasted.png")
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "photo", picture))
        var pasted: List<Uri>? = null
        show(onPasteMedia = { pasted = it })

        paste()

        assertThat(pasted).containsExactly(picture)
    }

    @Test
    fun severalPicturesInOnePasteAllArrive() {
        // One clip, two items: what selecting two photographs and copying produces. The
        // attachment path takes a list precisely so this does not have to be a paste each.
        val first = provided("first.png")
        val second = provided("second.png")
        val clip = ClipData.newUri(context.contentResolver, "photos", first).apply {
            addItem(ClipData.Item(second))
        }
        clipboard.setPrimaryClip(clip)
        var pasted: List<Uri>? = null
        show(onPasteMedia = { pasted = it })

        paste()

        assertThat(pasted).containsExactly(first, second).inOrder()
    }

    @Test
    fun pastedWordsStillGoIntoTheField() {
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "a question from elsewhere"))
        var pasted: List<Uri>? = null
        show(onPasteMedia = { pasted = it })

        paste()

        assertThat(pasted).isNull()
        compose.onNodeWithText("a question from elsewhere").assertExists()
    }

    @Test
    fun aComposerThatCannotBeTypedIntoCannotBePastedIntoEither() {
        // While a goal owns the conversation there is no message being written for an
        // attachment to belong to. The platform settles this before the composer has to:
        // a disabled field takes neither focus nor a paste, so the clipboard never reaches
        // the receiver at all. The composer's own guard is the second lock on that door,
        // for the drag-and-drop path, which does not ask the field for focus first.
        val picture = provided("refused.png")
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "photo", picture))
        var pasted: List<Uri>? = null
        show(enabled = false, onPasteMedia = { pasted = it })

        compose.onNodeWithContentDescription("Message").assertIsNotEnabled()
        assertThat(pasted).isNull()
    }

    private fun paste() {
        compose.onNodeWithContentDescription("Message")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithContentDescription("Message")
            .performSemanticsAction(SemanticsActions.PasteText)
        compose.waitForIdle()
    }

    /** A real PNG behind the app's own provider, which is what makes the type honest. */
    private fun provided(name: String): Uri {
        val captures = File(context.filesDir, "captures").apply { mkdirs() }
        val file = File(captures, name)
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.RED)
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private fun show(enabled: Boolean = true, onPasteMedia: (List<Uri>) -> Unit) {
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                Composer(
                    conversationKey = null,
                    enabled = enabled,
                    isGenerating = false,
                    staged = emptyList(),
                    document = null,
                    onRemoveDocument = {},
                    isAttaching = false,
                    canDictate = false,
                    isListening = false,
                    heard = "",
                    onAttach = {},
                    onRemoveStaged = {},
                    onDictate = {},
                    onSend = { true },
                    onStop = {},
                    onCommand = {},
                    onPasteMedia = onPasteMedia,
                )
            }
        }
    }
}

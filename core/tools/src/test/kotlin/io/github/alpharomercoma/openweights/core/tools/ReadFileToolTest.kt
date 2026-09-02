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

package io.github.alpharomercoma.openweights.core.tools

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboCursor
import java.io.ByteArrayInputStream

/**
 * What read_file does with the one number the model controls.
 *
 * The folder is a provider answering through Robolectric's resolver: one cursor for the
 * children query and one stream per open, which is enough to walk the real [Workspace]
 * rather than a stand-in for it. That matters here because the contract under test is
 * the join between the two: the offset the tool hands back in its "Cut here" line is the
 * offset the next call reads from, to the character, whatever bytes the file holds.
 */
@RunWith(RobolectricTestRunner::class)
class ReadFileToolTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tree: Uri = Uri.parse("content://openweights.test/tree/root")
    private val workspace =
        Workspace(context, WorkspaceGrant(context).also { it.remember(tree) })
    private val tool = ReadFileTool(workspace)

    /** How many times the file was opened, which for some offsets should be never. */
    private var opened = 0

    /** The one row the folder listing answers with: id, type, name and reported size. */
    private var listing: Array<Any?> = emptyArray()

    /**
     * Puts one text file in the shared folder, sized as [reported]; null stands for a
     * provider that does not say, which the contract allows.
     */
    private fun share(text: String, reported: Long? = text.toByteArray().size.toLong()) {
        listing = arrayOf("doc-1", "text/plain", "notes.txt", reported)
        val bytes = text.toByteArray()
        shadowOf(context.contentResolver).registerInputStreamSupplier(
            DocumentsContract.buildDocumentUriUsingTree(tree, "doc-1"),
        ) {
            opened++
            ByteArrayInputStream(bytes)
        }
    }

    /** A cursor answers once and the walk closes it, so every call is given a new one. */
    private fun listFolder() {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        shadowOf(context.contentResolver).setCursor(
            children,
            RoboCursor().apply {
                setColumnNames(
                    listOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                )
                setResults(arrayOf(listing))
            },
        )
    }

    private suspend fun read(offset: Any? = null): ToolExecution {
        listFolder()
        val arguments = if (offset == null) {
            """{"path":"notes.txt"}"""
        } else {
            """{"path":"notes.txt","offset":$offset}"""
        }
        return tool.execute(ToolCall(id = "1", name = "read_file", argumentsJson = arguments))
    }

    @Test
    fun `no offset reads from the start`() = runTest {
        share("hello, world")

        val execution = read()

        assertThat(execution.successful).isTrue()
        assertThat(execution.text).isEqualTo("hello, world")
    }

    @Test
    fun `the second page starts where the first one said it would`() = runTest {
        val text = (1..700).joinToString("\n") { "line $it" }
        share(text)

        val first = read()
        val second = read(WINDOW)

        assertThat(first.text).startsWith(text.take(WINDOW))
        assertThat(first.text).contains("offset $WINDOW")
        assertThat(second.text).isEqualTo(text.substring(WINDOW))
    }

    @Test
    fun `an offset past the end is answered without opening the file`() = runTest {
        share("short")

        // Beyond an Int, which used to parse as no offset at all and read page one.
        val far = read(5_000_000_000L)
        // Just past the reported size, which is where a paging loop overruns to.
        val near = read(6)

        assertThat(far.successful).isFalse()
        assertThat(far.retryable).isFalse()
        assertThat(far.text)
            .isEqualTo("notes.txt has nothing more to read from character 5000000000.")
        assertThat(near.text).isEqualTo("notes.txt has nothing more to read from character 6.")
        assertThat(opened).isEqualTo(0)
    }

    @Test
    fun `an offset at the end is read, and answered in the same words`() = runTest {
        // Five bytes can be fewer than five characters, so the size alone cannot say.
        share("short")

        val execution = read(5)

        assertThat(execution.successful).isFalse()
        assertThat(execution.text)
            .isEqualTo("notes.txt has nothing more to read from character 5.")
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `a negative offset is refused rather than read from the start`() = runTest {
        share("hello")

        val execution = read(-5)

        assertThat(execution.successful).isFalse()
        assertThat(execution.retryable).isFalse()
        assertThat(execution.text).contains("-5")
        assertThat(execution.text).contains("offset 0")
        assertThat(opened).isEqualTo(0)
    }

    @Test
    fun `a size the provider does not report is found out by reading`() = runTest {
        share("short", reported = null)

        assertThat(read(3).text).isEqualTo("rt")
        assertThat(read(50).text)
            .isEqualTo("notes.txt has nothing more to read from character 50.")
        assertThat(opened).isEqualTo(2)
    }

    @Test
    fun `every offset reads what the decoder counts, whatever the bytes`() = runTest {
        // ASCII past the size of one skip chunk, then letters of two bytes, then a face of
        // four, which is two characters to the count and a place an offset can land in the
        // middle of. The skip runs over bytes for the ASCII and over the decoder after it,
        // and each has to hand the next exactly the character the model was told to ask for.
        val text = "a".repeat(9_000) + "é ö 🙂 z"
        share(text)

        val offsets = listOf(0, 1, 4_000, 8_191, 8_192, 8_193) + (8_990..text.length)
        offsets.forEach { offset ->
            val expected = text.substring(offset)
            val execution = read(offset)
            val at = assertWithMessage("offset %s", offset)
            when {
                expected.isEmpty() -> at.that(execution.successful).isFalse()
                expected.length > WINDOW -> {
                    at.that(execution.text).startsWith(expected.take(WINDOW))
                    at.that(execution.text).contains("offset ${offset + WINDOW}")
                }
                else -> at.that(execution.text).isEqualTo(expected)
            }
        }
    }

    private companion object {
        /** What one call returns before it cuts, as the tool states it in its own text. */
        const val WINDOW = 4_000
    }
}

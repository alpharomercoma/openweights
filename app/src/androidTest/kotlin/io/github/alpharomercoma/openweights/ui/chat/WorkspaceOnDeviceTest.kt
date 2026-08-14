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
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.tools.ReadFileTool
import io.github.alpharomercoma.openweights.core.tools.SearchFilesTool
import io.github.alpharomercoma.openweights.core.tools.Workspace
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import io.github.alpharomercoma.openweights.core.tools.WriteFileTool
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a file tool reporting success has actually done something.
 *
 * The one class of failure nothing else here can catch: a tool that answers "Saved to
 * notes.md" while nothing was written. Every other check in the suite reads what the tool
 * said, and what the tool said is exactly what a tool with no effect would also say. The only
 * proof is to go and look, so each write is read back through a different tool.
 *
 * **This test needs a folder shared through the picker, and skips without one.** A grant comes
 * from a person tapping a system dialog and cannot be arranged from a shell, so on a device
 * cloud instance this reports skipped rather than passing. Run it on a phone where the folder
 * row in Settings shows a folder. Making it unconditional would mean shipping a stub
 * DocumentsProvider in the test APK to grant a temporary directory against, which is the way
 * to do it properly and is not done yet.
 *
 * It cleans up after itself through [DocumentsContract] rather than through a tool, because
 * there is deliberately no delete tool, and a test that leaves files in somebody's Documents
 * folder is a test that has broken something. Deletion failing fails the test, so litter is
 * loud rather than silent.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workspace = Workspace(context, WorkspaceGrant(context))
    private val write = WriteFileTool(workspace)
    private val read = ReadFileTool(workspace)
    private val find = SearchFilesTool(workspace)

    /** Unmistakably ours, and different every run, so nothing collides with a real file. */
    private val name = "openweights-test-${System.nanoTime()}.md"

    @Test
    fun whatWriteFileSaysItSavedIsThereToBeRead() = runBlocking<Unit> {
        assumeTrue("no folder has been shared with the app", workspace.isReady)
        assumeTrue("the shared folder is read only", workspace.acceptsNewFiles)

        try {
            val said = write.run(call("""{"path":"$name","content":"$CONTENT"}"""))
            assertThat(said).doesNotContain("could not")

            // Through a different tool, because the claim under test is the writer's and a
            // writer cannot be its own witness.
            val back = read.run(call("""{"path":"$name"}"""))
            assertThat(back).contains(CONTENT)

            // And by name, so the file is visible to the tool a model would reach for first.
            val listed = find.run(call("""{"pattern":"openweights-test-*"}"""))
            assertThat(listed).contains(name)
        } finally {
            remove(name)
        }
    }

    @Test
    fun writingOverSomethingThatExistsIsRefusedAndChangesNothing() = runBlocking<Unit> {
        assumeTrue("no folder has been shared with the app", workspace.isReady)
        assumeTrue("the shared folder is read only", workspace.acceptsNewFiles)

        try {
            write.run(call("""{"path":"$name","content":"$CONTENT"}"""))

            val refused = write.run(call("""{"path":"$name","content":"something else"}"""))

            assertThat(refused).contains("already exists")
            // The load-bearing half. A refusal that had already truncated the file would be
            // worse than no refusal at all, and this is the failure create-only exists to
            // make impossible.
            assertThat(read.run(call("""{"path":"$name"}"""))).contains(CONTENT)
        } finally {
            remove(name)
        }
    }

    @Test
    fun aLongFileSaysWhereToPickItUpAndPickingItUpWorks() = runBlocking<Unit> {
        assumeTrue("no folder has been shared with the app", workspace.isReady)
        assumeTrue("the shared folder is read only", workspace.acceptsNewFiles)

        // Two thousand characters of a repeating marker, then a word that only appears past
        // the first window. If the second read is a second copy of the first page, the word
        // is not in it.
        val body = "ab".repeat(HALF_OF_LONG) + END_MARKER
        try {
            write.run(call("""{"path":"$name","content":"$body"}"""))

            val first = read.run(call("""{"path":"$name"}"""))
            assertThat(first).contains("Cut here")
            assertThat(first).doesNotContain(END_MARKER)

            val second = read.run(call("""{"path":"$name","offset":"$WINDOW"}"""))
            assertThat(second).contains(END_MARKER)
        } finally {
            remove(name)
        }
    }

    private fun call(arguments: String) = ToolCall("1", "t", arguments)

    /**
     * Takes the file away again, using the API the product does not expose.
     *
     * Delete and rename have no undo on Android, which is why no tool offers them. A test is a
     * different case: it created this file, it knows the exact document, and leaving it behind
     * would be the app littering somebody's folder.
     */
    private suspend fun remove(path: String) {
        val entry = workspace.resolve(path) ?: return
        val uri = requireNotNull(workspace.uriFor(entry)) { "no uri for $path" }
        val gone = DocumentsContract.deleteDocument(context.contentResolver, uri)
        assertThat(gone).isTrue()
    }

    private companion object {
        const val CONTENT = "the kettle is on"

        /** The same window read_file uses, so the second call starts exactly where it cut. */
        const val WINDOW = 1_500

        /** Enough repetitions to overrun the window twice over. */
        const val HALF_OF_LONG = 1_000

        /** Only reachable past the first window, so a repeated page cannot fake it. */
        const val END_MARKER = "endstop"
    }
}

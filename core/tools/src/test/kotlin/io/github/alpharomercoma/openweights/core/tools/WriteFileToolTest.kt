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
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What write_file does with a call a small model wrote.
 *
 * The folder is the same fake provider [ReadFileToolTest] walks, so what is proven is the
 * whole path from the call's text to the bytes on disk, not the argument reader alone.
 */
@RunWith(RobolectricTestRunner::class)
class WriteFileToolTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provider = FakeDocumentsProvider.register()
    private val workspace = Workspace(
        context,
        WorkspaceGrant(context).also { it.remember(FakeDocumentsProvider.TREE) },
    )
    private val tool = WriteFileTool(workspace, SessionArtifacts(), CanvasBoard())

    private suspend fun write(arguments: String): ToolExecution =
        tool.execute(ToolCall(id = "1", name = "write_file", argumentsJson = arguments))

    @Test
    fun `a quote the model left unescaped in the content does not lose the path`() = runTest {
        // The content's salvaged reading exists for exactly this call, and was never reached:
        // the path was read by parsing the whole envelope, which the quote had broken, so
        // the call was refused for want of a path it plainly gave.
        val execution = write("""{"path":"notes.txt","content":"He said "hi" and left"}""")

        assertThat(execution.successful).isTrue()
        val entry = checkNotNull(workspace.resolve("notes.txt"))
        assertThat(workspace.readBytes(entry)?.toString(Charsets.UTF_8))
            .isEqualTo("""He said "hi" and left""")
    }

    @Test
    fun `a call with no path at all is still refused`() = runTest {
        val execution = write("""{"content":"He said "hi" and left"}""")

        assertThat(execution.successful).isFalse()
        assertThat(execution.text).contains("No path")
        assertThat(provider.opens).isEmpty()
    }
}

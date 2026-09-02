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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the shared folder holds at each point of a replacement, put to a provider.
 *
 * The provider is a small one of the tests' own over a directory on the host, because the
 * real ones exist only on a device and the property under test is not theirs. It is that
 * the old text stays until the new is all there, whatever a provider makes of a rename,
 * and that a failure leaves the folder as it was and says so.
 */
@RunWith(RobolectricTestRunner::class)
class WorkspaceReplaceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val provider = FakeDocumentsProvider.register()
    private val workspace = Workspace(
        context,
        WorkspaceGrant(context).apply { remember(FakeDocumentsProvider.TREE) },
    )

    private fun read(path: String): String? = runBlocking {
        workspace.readText(requireNotNull(workspace.resolve(path)), skip = 0, take = 1_000)
    }

    private fun names(under: String = ""): List<String> = runBlocking {
        val entries = if (under.isEmpty()) {
            workspace.root()
        } else {
            workspace.children(requireNotNull(workspace.resolve(under)))
        }
        entries.map { it.name }
    }

    @Test
    fun `replacing swaps the new text in and leaves nothing staged behind`() {
        runBlocking {
            workspace.put("notes/todo.md", "buy milk")

            val result = workspace.put("notes/todo.md", "buy oat milk", replace = true)

            assertThat(result.text).isEqualTo("Replaced notes/todo.md with 12 characters.")
            assertThat(result.successful).isTrue()
            assertThat(read("notes/todo.md")).isEqualTo("buy oat milk")
            assertThat(names("notes")).containsExactly("todo.md")
            // The file was opened for writing once, to be made. The replacement never
            // opened it: what is under its name now was written whole under another
            // name and renamed.
            assertThat(provider.opens.filter { it.startsWith("todo.md:") && 'w' in it })
                .hasSize(1)
        }
    }

    @Test
    fun `a provider that cannot write the staged copy leaves the original as it was`() {
        runBlocking {
            workspace.put("todo.md", "buy milk")
            provider.refusesWritesTo = { it.endsWith(".tmp") }

            val result = workspace.put("todo.md", "buy oat milk", replace = true)

            assertThat(result.successful).isFalse()
            assertThat(result.text).isEqualTo("todo.md could not be written, so it is unchanged.")
            assertThat(read("todo.md")).isEqualTo("buy milk")
            assertThat(names()).containsExactly("todo.md")
        }
    }

    @Test
    fun `a provider that cannot rename still ends with the text under the right name`() {
        runBlocking {
            workspace.put("todo.md", "buy milk")
            provider.renames = false

            val result = workspace.put("todo.md", "buy oat milk", replace = true)

            assertThat(result.text).isEqualTo("Replaced todo.md with 12 characters.")
            assertThat(result.successful).isTrue()
            assertThat(read("todo.md")).isEqualTo("buy oat milk")
            assertThat(names()).containsExactly("todo.md")
        }
    }
}

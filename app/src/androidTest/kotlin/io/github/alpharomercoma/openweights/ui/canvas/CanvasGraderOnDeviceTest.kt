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

package io.github.alpharomercoma.openweights.ui.canvas

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.tools.CanvasBoard
import io.github.alpharomercoma.openweights.core.tools.CanvasGrader
import io.github.alpharomercoma.openweights.core.tools.CanvasKind
import io.github.alpharomercoma.openweights.core.tools.CanvasServer
import io.github.alpharomercoma.openweights.core.tools.WebViewPageChecker
import io.github.alpharomercoma.openweights.core.tools.Workspace
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The grader against the real browser, the real server and the real folder.
 *
 * The unit test fakes the browser; what it cannot say is whether a WebView loading a page
 * over this app's loopback server, under the server's own content policy, reports the
 * error a page raised, and reports it by file and line. This writes a page with one
 * uncaught exception and one stylesheet it never saved, shows it, and reads the verdict.
 *
 * **Needs a folder shared through the picker, and skips without one**, like every
 * on-device workspace test. The files are removed afterwards through the API the product
 * does not expose.
 */
@RunWith(AndroidJUnit4::class)
class CanvasGraderOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workspace = Workspace(context, WorkspaceGrant(context))
    private val board = CanvasBoard()
    private val server = CanvasServer(workspace, board, context)
    private val grader = CanvasGrader(board, server, WebViewPageChecker(context))

    private val folder = "openweights-grader-${System.nanoTime()}"

    @Test
    fun aPageThatThrowsIsReportedByFileAndLine() = runBlocking<Unit> {
        assumeTrue("no folder has been shared with the app", workspace.isReady)
        assumeTrue("the shared folder is read only", workspace.acceptsNewFiles)
        try {
            val page = "$folder/index.html"
            put(page, BROKEN_PAGE)
            board.show(CanvasKind.SITE, page, folder)

            val verdict = grader.verdict(page)

            assertThat(verdict).isNotNull()
            assertThat(verdict).contains("The page raised 2 errors when it loaded.")
            assertThat(verdict).contains("ReferenceError")
            assertThat(verdict).contains("addItem")
            assertThat(verdict).contains("(index.html:6)")
            assertThat(verdict).contains("Missing file: missing.css")
        } finally {
            server.stop()
            remove("$folder/index.html")
            remove(folder)
        }
    }

    @Test
    fun aCleanPageSaysNothing() = runBlocking<Unit> {
        assumeTrue("no folder has been shared with the app", workspace.isReady)
        assumeTrue("the shared folder is read only", workspace.acceptsNewFiles)
        try {
            val page = "$folder/index.html"
            put(page, CLEAN_PAGE)
            board.show(CanvasKind.SITE, page, folder)

            assertThat(grader.verdict(page)).isNull()
        } finally {
            server.stop()
            remove("$folder/index.html")
            remove(folder)
        }
    }

    private suspend fun put(path: String, content: String) {
        val written = workspace.put(path, content)
        check(written.successful) { written.text }
    }

    private suspend fun remove(path: String) {
        val entry = workspace.resolve(path) ?: return
        val uri = requireNotNull(workspace.uriFor(entry)) { "no uri for $path" }
        assertThat(DocumentsContract.deleteDocument(context.contentResolver, uri)).isTrue()
    }

    private companion object {
        // Line 6 is the call to a function that does not exist; the stylesheet on line 3
        // was never saved. Both are what a small model does most.
        val BROKEN_PAGE = """
            <!doctype html>
            <html><head>
            <link rel="stylesheet" href="missing.css">
            </head><body>
            <h1>Todo</h1>
            <script>addItem("first");</script>
            </body></html>
        """.trimIndent()

        val CLEAN_PAGE = """
            <!doctype html>
            <html><head><style>h1 { color: teal }</style></head>
            <body><h1>Todo</h1><script>document.title = "ok";</script></body></html>
        """.trimIndent()
    }
}

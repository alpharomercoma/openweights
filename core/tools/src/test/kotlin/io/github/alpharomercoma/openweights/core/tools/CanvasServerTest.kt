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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.URL

/**
 * The viewer routes, exercised over a real socket.
 *
 * The file routes are [Workspace.resolve]'s tests one directory over; what is proven here
 * is the machinery the viewers add: shells that embed the asked-for file safely, assets
 * that come from the APK and nowhere else, and nothing new to reach beyond them.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasServerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val server = CanvasServer(Workspace(context, WorkspaceGrant(context)), context)

    private fun get(pathAndQuery: String): Pair<Int, String> {
        val connection =
            URL("http://127.0.0.1:${server.port()}$pathAndQuery").openConnection()
                as HttpURLConnection
        val code = connection.responseCode
        val body = (if (code < 400) connection.inputStream else connection.errorStream)
            ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        connection.disconnect()
        return code to body
    }

    @Test
    fun `the document viewer embeds the asked-for file`() {
        val (code, body) = get("/__ow__/doc?file=notes%2Freport.md")
        assertThat(code).isEqualTo(200)
        assertThat(body).contains("\"notes/report.md\"")
        // The A4 promise lives in the print stylesheet the shell hands to the paginator —
        // it moved there because Paged.js only honours @page rules in sheets it is given.
        assertThat(body).contains("doc-paged.css")
        val (cssCode, css) = get("/__ow__/asset/doc-paged.css")
        assertThat(cssCode).isEqualTo(200)
        assertThat(css).contains("size: A4")
    }

    @Test
    fun `the deck viewer embeds the file and the 16-9 stage`() {
        val (code, body) = get("/__ow__/deck?file=talk%2Fslides.md")
        assertThat(code).isEqualTo(200)
        assertThat(body).contains("\"talk/slides.md\"")
        assertThat(body).contains("width: 1280px")
        assertThat(body).contains("height: 720px")
    }

    @Test
    fun `a file name cannot break out of the shell's string literal`() {
        val (code, body) = get("/__ow__/doc?file=%22%3C%2Fscript%3E%3Cscript%3Ealert(1)")
        assertThat(code).isEqualTo(200)
        assertThat(body).doesNotContain("</script><script>alert(1)")
    }

    @Test
    fun `viewer assets come from the APK`() {
        val (code, body) = get("/__ow__/asset/marked.min.js")
        assertThat(code).isEqualTo(200)
        assertThat(body).contains("marked")
    }

    @Test
    fun `asset names are reduced to their last segment`() {
        // A walk written into the name reads as a missing asset, never as a walk.
        val (code, _) = get("/__ow__/asset/..%2F..%2FAndroidManifest.xml")
        assertThat(code).isEqualTo(404)
    }

    @Test
    fun `an unknown viewer answers 404 rather than a file lookup`() {
        val (code, body) = get("/__ow__/nothing")
        assertThat(code).isEqualTo(404)
        assertThat(body).contains("No such viewer")
    }

    @Test
    fun `viewer urls name the kinds the screen asks for`() {
        assertThat(server.viewerUrlFor("doc", "a b/report.md"))
            .endsWith("/__ow__/doc?file=a+b%2Freport.md")
        assertThat(server.viewerUrlFor("deck", "slides.md"))
            .endsWith("/__ow__/deck?file=slides.md")
    }
}

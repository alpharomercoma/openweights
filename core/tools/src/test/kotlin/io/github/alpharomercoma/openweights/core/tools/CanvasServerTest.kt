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
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * The viewer routes, exercised over a real socket.
 *
 * The file routes are [Workspace.resolve]'s tests one directory over; what is proven here
 * is the machinery the viewers add: shells that embed the asked-for file safely, assets
 * that come from the APK and nowhere else, and nothing new to reach beyond them.
 *
 * And the door. Loopback is shared by every app on the phone, so a request that does not
 * carry the key this process minted, or names another host, gets nothing.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasServerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val board = CanvasBoard()
    private val server = CanvasServer(Workspace(context, WorkspaceGrant(context)), board, context)

    private fun get(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        val code = connection.responseCode
        val body = (if (code < 400) connection.inputStream else connection.errorStream)
            ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        connection.disconnect()
        return code to body
    }

    /**
     * The status code for a request written by hand, so the Host header can say anything.
     * HttpURLConnection refuses to send a Host of the caller's choosing.
     */
    private fun statusOf(url: String, host: String): Int {
        val target = URL(url)
        Socket(target.host, target.port).use { socket ->
            val request = "GET ${target.file} HTTP/1.1\r\nHost: $host\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            val status = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
            return status.split(' ')[1].toInt()
        }
    }

    /** The key in this server's URLs: the first path segment. */
    private fun key(): String = URL(server.viewerUrlFor("doc", "x")).path.split('/')[1]

    /** A URL the server would answer, with its key, for a viewer route. */
    private fun viewer(pathAndQuery: String): String =
        server.viewerUrlFor("doc", "x").substringBefore("/__ow__/") + pathAndQuery

    @Test
    fun `the document viewer embeds the asked-for file`() {
        val (code, body) = get(server.viewerUrlFor("doc", "notes/report.md"))
        assertThat(code).isEqualTo(200)
        assertThat(body).contains("\"notes/report.md\"")
        // The A4 promise lives in the print stylesheet the shell hands to the paginator —
        // it moved there because Paged.js only honours @page rules in sheets it is given.
        assertThat(body).contains("doc-paged.css")
        val (cssCode, css) = get(viewer("/__ow__/asset/doc-paged.css"))
        assertThat(cssCode).isEqualTo(200)
        assertThat(css).contains("size: A4")
    }

    @Test
    fun `the shell reaches its assets and its file through the keyed base`() {
        // Every absolute path the shell uses has to carry the key, or the page would ask
        // the server for things the server no longer answers.
        val (_, body) = get(server.viewerUrlFor("doc", "notes/report.md"))
        val key = key()
        assertThat(key).hasLength(32)
        assertThat(body).contains("\"/$key/__ow__/asset/marked.min.js\"")
        assertThat(body).contains("fetch(\"/$key/\"")
        assertThat(body).doesNotContain("__OW_BASE__")
    }

    @Test
    fun `the deck viewer embeds the file and the 16-9 stage`() {
        val (code, body) = get(server.viewerUrlFor("deck", "talk/slides.md"))
        assertThat(code).isEqualTo(200)
        assertThat(body).contains("\"talk/slides.md\"")
        assertThat(body).contains("width: 1280px")
        assertThat(body).contains("height: 720px")
    }

    @Test
    fun `a file name cannot break out of the shell's string literal`() {
        val (code, body) = get(server.viewerUrlFor("doc", "\"</script><script>alert(1)"))
        assertThat(code).isEqualTo(200)
        assertThat(body).doesNotContain("</script><script>alert(1)")
    }

    @Test
    fun `viewer assets come from the APK`() {
        val (code, body) = get(viewer("/__ow__/asset/marked.min.js"))
        assertThat(code).isEqualTo(200)
        assertThat(body).contains("marked")
    }

    @Test
    fun `asset names are reduced to their last segment`() {
        // A walk written into the name reads as a missing asset, never as a walk.
        val (code, _) = get(viewer("/__ow__/asset/..%2F..%2FAndroidManifest.xml"))
        assertThat(code).isEqualTo(404)
    }

    @Test
    fun `an unknown viewer answers 404 rather than a file lookup`() {
        val (code, body) = get(viewer("/__ow__/nothing"))
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

    @Test
    fun `a request without the key gets nothing, viewer or file`() {
        // What another app on the phone can send: the port is guessable, the key is not.
        // The answer is the same 404 a missing file gets, so a scan learns nothing.
        val port = server.port()
        val (viewerCode, viewerBody) = get("http://127.0.0.1:$port/__ow__/asset/marked.min.js")
        assertThat(viewerCode).isEqualTo(404)
        assertThat(viewerBody).doesNotContain("marked(")
        val (fileCode, _) = get("http://127.0.0.1:$port/notes/passwords.md")
        assertThat(fileCode).isEqualTo(404)
        val (wrongKeyCode, _) =
            get("http://127.0.0.1:$port/${"0".repeat(32)}/__ow__/asset/marked.min.js")
        assertThat(wrongKeyCode).isEqualTo(404)
    }

    @Test
    fun `a request for another host is refused`() {
        // A browser that resolved somebody else's name to this address still says whose
        // name it asked for, and this server answers to one address only.
        val asset = viewer("/__ow__/asset/marked.min.js")
        assertThat(statusOf(asset, host = "evil.example:80")).isEqualTo(400)
        assertThat(statusOf(asset, host = "127.0.0.1:${server.port()}")).isEqualTo(200)
    }

    @Test
    fun `a file is served only while a canvas shows it`() {
        // No canvas on screen, nothing served; a canvas showing one folder does not serve
        // another. With no folder granted the answer is 404 either way, so what is pinned
        // here is that the routes reach the file lookup gated, not the lookup itself: see
        // CanvasBoardTest for which paths a canvas owns.
        val (code, _) = get(server.urlFor("notes/passwords.md"))
        assertThat(code).isEqualTo(404)
        board.show(CanvasKind.SITE, "site/index.html", "site")
        val (outsideCode, _) = get(server.urlFor("notes/passwords.md"))
        assertThat(outsideCode).isEqualTo(404)
    }

    @Test
    fun `stopping closes the socket and mints a new key`() {
        val before = server.viewerUrlFor("doc", "x")
        val keyBefore = key()

        server.stop()

        val refused = runCatching { get(before) }.exceptionOrNull()
        assertThat(refused).isInstanceOf(ConnectException::class.java)
        // The next use starts a fresh server, and nothing that learned the old URL can
        // read from it: the key is new whatever port the socket lands on.
        assertThat(key()).isNotEqualTo(keyBefore)
        val (code, _) = get(server.viewerUrlFor("doc", "y"))
        assertThat(code).isEqualTo(200)
    }
}

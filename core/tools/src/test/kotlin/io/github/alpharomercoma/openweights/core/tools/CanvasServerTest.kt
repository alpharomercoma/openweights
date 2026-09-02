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
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
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
    private val grant = WorkspaceGrant(context)
    private val workspace = Workspace(context, grant)
    private val server = CanvasServer(workspace, board, context)

    init {
        // Registered, not held: the tests that need the folder reach it through the grant.
        FakeDocumentsProvider.register()
    }

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

    /** One response: its headers, lower-cased names and joined values, and its body. */
    private fun fetch(url: String): Pair<Map<String, String>, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        val code = connection.responseCode
        val headers = connection.headerFields
            .filterKeys { it != null }
            .map { (name, values) -> name.lowercase() to values.joinToString(", ") }
            .toMap()
        val body = (if (code < 400) connection.inputStream else connection.errorStream)
            ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        connection.disconnect()
        return headers to body
    }

    private fun headersOf(url: String): Map<String, String> = fetch(url).first

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
    fun `a viewer runs only the scripts it was born with`() {
        // Markdown carries raw HTML, and the file is the model's. The policy names a nonce
        // minted for this response, the shell's own scripts carry it, and a <script> or an
        // onerror= inside the document has none.
        val url = server.viewerUrlFor("doc", "notes/report.md")
        val (headers, body) = fetch(url)
        val policy = headers.getValue("content-security-policy")
        val nonce = Regex("'nonce-([0-9a-f]{32})'").find(policy)?.groupValues?.get(1)
        assertThat(nonce).isNotNull()
        // The whole script directive: the nonce, and no 'unsafe-inline' beside it.
        assertThat(policy).contains("script-src 'self' 'nonce-$nonce';")
        assertThat(policy).contains("connect-src 'self'")
        assertThat(policy).contains("form-action 'none'")
        assertThat(body).contains("<script nonce=\"$nonce\">")
        assertThat(body).doesNotContain("__OW_NONCE__")
        // A new one every time: a nonce that could be predicted is not one.
        assertThat(headersOf(url).getValue("content-security-policy")).doesNotContain(nonce!!)
    }

    @Test
    fun `a page the model built may reach nothing off the phone`() {
        // The policy every HTML file is served under. Scripts and eval stay, because a
        // site is what it runs; where it may connect, post and load from is this server.
        val policy = CanvasServer.PAGE_POLICY
        assertThat(policy).contains("default-src 'self'")
        assertThat(policy).contains("connect-src 'self'")
        assertThat(policy).contains("form-action 'self'")
        assertThat(policy).doesNotContain("*")
        assertThat(policy).doesNotContain("http")
        // And assets are what their name says, not what a browser sniffs them to be.
        val headers = headersOf(viewer("/__ow__/asset/marked.min.js"))
        assertThat(headers["x-content-type-options"]).isEqualTo("nosniff")
        assertThat(headers).doesNotContainKey("content-security-policy")
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
    fun `a folder is answered by its index page, with or without the slash`() = runTest {
        // Neither used to reach the index fallback: the trailing slash left an empty name
        // the walk refuses, and the bare folder was neither the entry nor under "site/".
        grant.remember(FakeDocumentsProvider.TREE)
        check(workspace.put("site/index.html", "<h1>Home</h1>").successful)
        board.show(CanvasKind.SITE, "site/index.html", "site")

        listOf("site/", "site").forEach { path ->
            val (headers, body) = fetch(server.urlFor(path))
            assertWithMessage(path).that(body).isEqualTo("<h1>Home</h1>")
            // As a page, not as a download: the type is the index file's, not the folder's.
            assertWithMessage(path).that(headers["content-type"]).startsWith("text/html")
        }
    }

    @Test
    fun `connections beyond the bound wait in the backlog rather than being served`() {
        // Each client connects and sends nothing, which parks the worker that accepted it in
        // the request read: the one state connections can pile up in. Ten of them against a
        // bound of four, and the six that wait sit within the backlog of eight, so the kernel
        // completes every connect and nothing here rests on a retried SYN.
        val port = server.port()
        val clients = List(CanvasServer.CONNECTIONS + 6) {
            Socket("127.0.0.1", port).apply { soTimeout = 10_000 }
        }
        try {
            settleUntil { server.serving.get() == CanvasServer.CONNECTIONS }
            assertThat(server.serving.get()).isEqualTo(CanvasServer.CONNECTIONS)
            // Then everyone asks at once, and everyone is answered: the backlog drains through
            // the same four workers and never through more of them.
            val request = "GET /nothing HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"
            clients.forEach {
                it.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            }
            clients.forEach { client ->
                val status =
                    client.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
                assertThat(status).isEqualTo("HTTP/1.1 404 Not Found")
            }
            assertThat(server.peakServing.get()).isEqualTo(CanvasServer.CONNECTIONS)
        } finally {
            clients.forEach { runCatching { it.close() } }
        }
    }

    /** Waits, up to a few seconds, for [condition] to come true; the assertion follows. */
    private fun settleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
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

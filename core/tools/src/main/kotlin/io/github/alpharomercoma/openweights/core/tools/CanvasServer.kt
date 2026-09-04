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
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A web server for an audience of one phone.
 *
 * Serves the shared folder over loopback so two browsers can read it: the in-app WebView,
 * and whatever real browser the user prefers — a `content://` document can reach neither,
 * and every browser understands `http://127.0.0.1`. Bound to the loopback interface
 * explicitly, so nothing on the network can reach it; the URL never leaves the device.
 *
 * GET only, one request per connection, files resolved through [Workspace.resolve] — the
 * same walk every file tool uses, which is what makes `../` inert here: a path is either
 * a chain of names the granted folder handed over, or it is nothing. And a few connections
 * at a time, the rest waiting in the socket's backlog; [scope] says how many and why.
 *
 * ### Loopback is not private
 *
 * Every app on the phone shares 127.0.0.1, and `INTERNET` is a permission nobody is asked
 * about. A server that answered any `GET` therefore handed the folder the user shared with
 * this app to every other app installed, for as long as the process lived, and a scan of
 * the ephemeral port range finds it in seconds. So three things gate a request now, and
 * each one closes a different door:
 *
 * - **The key.** Every URL carries a random 128-bit path segment minted when the server
 *   starts, and a request without it is answered as if the file did not exist. Only what
 *   this app hands out — the WebView's URL, the Open-in-browser link — knows it. A cookie
 *   would not do: browsers scope cookies by host and not by port, so a key stored that way
 *   would be sent to any other app's loopback server the user later opened.
 * - **The host.** A browser resolving somebody else's name to 127.0.0.1 still sends that
 *   name as `Host`, so a request for anything but this address and port is refused.
 * - **The canvas.** Files are served only from under the folder the canvas is showing.
 *   A page the model built reads its own CSS; it does not read the notes beside it.
 *
 * And the server stops with the canvas: [stop] closes the socket and the key is minted
 * afresh next time, so a URL copied out of one session opens nothing in the next.
 *
 * ### Nothing leaves the phone
 *
 * The gates above decide who may read the folder. A page the model built could still
 * send it somewhere: a `fetch` to any host, a form posted anywhere, an image whose URL
 * carries the file. Every HTML response therefore carries a Content-Security-Policy, which
 * both the in-app WebView and a real browser enforce: a built page may run its own
 * scripts and load from its own folder, and may connect to nothing but this server. The
 * viewer shells go further, because the Markdown they render is a file the model wrote
 * and Markdown carries raw HTML: their policy runs only scripts marked with a nonce minted
 * per response, so a `<script>` or an `onerror=` inside a document is inert. The cost is
 * that a built site cannot pull fonts, images or scripts from a CDN, and the site tool
 * says so to the model.
 */
@Singleton
class CanvasServer @Inject constructor(
    private val workspace: Workspace,
    private val board: CanvasBoard,
    @param:ApplicationContext private val context: Context,
) {

    private var socket: ServerSocket? = null
    private var key: String = newKey()

    /**
     * The threads that answer requests, and there are [CONNECTIONS] of them.
     *
     * A view of the IO pool with a ceiling of its own, rather than the pool itself. A request
     * holds its thread from accept to the last byte written — for as long as the read timeout
     * allows, when a client connects and says nothing — and on the shared pool that was a
     * thread per connection, for as many connections as anything on the phone cared to open.
     * Four is plenty for an audience of one WebView, which fetches a page and a handful of
     * assets; a fifth connection waits in the socket's backlog, where it costs nothing, until
     * a worker is free to accept it. And a view of IO is elastic: these threads are on top of
     * the pool's own limit, so an open canvas takes nothing from the rest of the app, where
     * the old accept loop held one of its sixty-four for the life of the server.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(CONNECTIONS),
    )

    /**
     * Connections inside [serve] right now, and the most there have been at once.
     *
     * Read only by a test. The ceiling above is a claim about concurrency, and a claim is
     * checked by counting.
     */
    internal val serving = AtomicInteger()
    internal val peakServing = AtomicInteger()

    /** The port the server listens on, starting it on first use. */
    @Synchronized
    fun port(): Int {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        // The IPv4 loopback by its literal, not getLoopbackAddress(): that call answered
        // ::1 in some processes on MIUI, the URLs all say 127.0.0.1, and an IPv6-only
        // socket refuses every IPv4 connect — the canvas showed an error page while the
        // server listened perfectly well on an address nobody was dialling.
        val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        socket = server
        // One accept loop per worker. A worker accepts a connection, answers it, and only
        // then accepts the next, so nothing is spawned per connection and the queue is the
        // socket's backlog: however many clients connect, this many are ever being served.
        repeat(CONNECTIONS) { scope.launch { accept(server) } }
        return server.localPort
    }

    /**
     * Closes the socket and forgets the key.
     *
     * Called when the canvas is dismissed. The next [port] starts a fresh server under a
     * fresh key, so nothing that learned the old URL — a browser tab, another app that
     * read it off the screen — can read a file from it.
     */
    @Synchronized
    fun stop() {
        socket?.let { runCatching { it.close() } }
        socket = null
        key = newKey()
    }

    /** `http://127.0.0.1:port/key`, the prefix every URL this server answers begins with. */
    @Synchronized
    private fun base(): String = "http://127.0.0.1:${port()}/$key"

    fun urlFor(path: String): String {
        val encoded = path.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "${base()}/$encoded"
    }

    /**
     * The URL that shows [path] through one of the bundled viewers.
     *
     * `doc` lays Markdown out as real A4 pages; `deck` as 16:9 slides. Both are plain
     * pages any browser on this phone can open, which is the point: the canvas in the app
     * and the tab in Chrome are the same rendering, byte for byte.
     */
    fun viewerUrlFor(kind: String, path: String): String =
        "${base()}/$VIEWER_PREFIX/$kind?file=" + URLEncoder.encode(path, "UTF-8")

    /**
     * One worker's whole life: accept a connection, answer it, accept the next.
     *
     * Several workers block in `accept` on the one socket and the kernel hands each new
     * connection to one of them; closing the socket wakes them all, and they leave.
     */
    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            // Nothing a connection does may leave this loop. A worker is a root coroutine,
            // so an exception escaping here has no parent to hand it to: the coroutines
            // machinery gives it to the thread's uncaught handler, which on Android is the
            // one that kills the process. The worker is gone either way, so four bad
            // requests would have retired the whole server. Both were reachable from
            // outside: a socket the client reset mid-write, and a path any app on the
            // loopback could send that `URLDecoder` refuses.
            try {
                client.use { serve(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                Log.w(TAG, "canvas request failed", failure)
            }
        }
    }

    private fun serve(client: Socket) {
        val now = serving.incrementAndGet()
        peakServing.updateAndGet { most -> maxOf(most, now) }
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val reader =
                BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            val request = runCatching { reader.readLine() }.getOrNull() ?: return
            // The headers are drained; the one that matters is read on the way past.
            var host: String? = null
            var header = runCatching { reader.readLine() }.getOrNull()
            while (!header.isNullOrEmpty()) {
                if (header.startsWith("host:", ignoreCase = true)) {
                    host = header.substringAfter(':').trim()
                }
                header = runCatching { reader.readLine() }.getOrNull()
            }
            val out = client.getOutputStream()
            out.write(answer(request.split(' '), host, client.localPort))
            out.flush()
        } finally {
            serving.decrementAndGet()
        }
    }

    /** The whole reply to one request line, gates first. */
    private fun answer(parts: List<String>, host: String?, port: Int): ByteArray {
        if (parts.size < 2 || parts[0] != "GET") {
            return response("405 Method Not Allowed", "text/plain", "GET only".toByteArray())
        }
        if (host != "127.0.0.1:$port") {
            return response("400 Bad Request", "text/plain", "Wrong host".toByteArray())
        }
        // The path is decoded before the key is checked, so every app on the loopback gets
        // to choose what `URLDecoder` is handed, and it throws on a `%` with nothing usable
        // after it: one byte, on the unauthenticated side of the door.
        val decoded = decodeOrNull(parts[1].substringBefore('?'))?.trimStart('/')
        // Answered exactly like a missing file, so a scan learns nothing from the reply
        // about whether it guessed a key or a name. A path that will not decode at all is
        // told the same thing, for the same reason and because that is what it is.
        if (decoded == null ||
            decoded.substringBefore('/') != currentKey() ||
            !decoded.contains('/')
        ) {
            val body = "No such file: ${decoded.orEmpty()}".toByteArray()
            return response("404 Not Found", "text/plain", body)
        }
        return route(decoded.substringAfter('/', ""), parts[1].substringAfter('?', ""))
    }

    /** A viewer, or a file the canvas on screen owns. */
    private fun route(path: String, query: String): ByteArray {
        viewerResponse(path, query)?.let { return it }
        val (served, body) = runBlocking { read(path) }
            ?: return response("404 Not Found", "text/plain", "No such file: $path".toByteArray())
        // Typed by the file that answered, not by the name asked for: a folder answered by
        // its index page would otherwise go out as a download rather than a page.
        val type = contentTypeFor(served)
        val policy = if (type.startsWith("text/html")) PAGE_POLICY else null
        return response("200 OK", type, body, policy)
    }

    @Synchronized
    private fun currentKey(): String = key

    /**
     * A string safe to drop between the quotes of a JavaScript string literal.
     *
     * The file name reaches here from a query parameter, so it is whatever the model named
     * its file plus whatever anything on the loopback appends: percent-decoding turns
     * `%0A` into a real newline, and a newline inside a string literal is a syntax error
     * that stops the viewer's whole script — a blank canvas, with the reason only in a
     * console nobody is reading. `<` is escaped so the literal cannot close the `<script>`
     * element around it, and U+2028 and U+2029 because they were line terminators before
     * ES2019 and this string is also read by whatever browser the user opens it in.
     */
    private fun String.asJsStringBody(): String = buildString(length) {
        for (character in this@asJsStringBody) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '<' -> append("\\u003c")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                // Anything else a control character would do to the literal, it does by
                // being unreadable rather than by ending it; escaped for the same reason.
                in '\u0000'..'\u001f' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
    }

    /**
     * A percent-decoded path, or null when it is not one.
     *
     * `URLDecoder.decode` throws `IllegalArgumentException` on a trailing `%` and on
     * non-hex after one, and both are one byte for anything on the phone to send.
     */
    private fun decodeOrNull(raw: String): String? =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()

    /**
     * The bundled viewers and their assets, or null when [path] is an ordinary file.
     *
     * All read from the APK, never from the shared folder, so the user's files cannot
     * shadow the machinery that renders them; and the asset names are reduced to their
     * last segment, so the route cannot be walked anywhere else.
     */
    private fun viewerResponse(path: String, query: String): ByteArray? {
        if (!path.startsWith("$VIEWER_PREFIX/")) return null
        val rest = path.removePrefix("$VIEWER_PREFIX/")
        if (rest.startsWith("asset/")) {
            return assetResponse(rest.removePrefix("asset/").substringAfterLast('/'))
        }
        val template = when (rest) {
            "doc" -> "canvas/doc.html"
            "deck" -> "canvas/deck.html"
            else -> return response(
                "404 Not Found",
                "text/plain",
                "No such viewer: $rest".toByteArray(),
            )
        }
        return shellResponse(template, query)
    }

    private fun assetResponse(name: String): ByteArray {
        val body = runCatching {
            context.assets.open("canvas/$name").use { it.readBytes() }
        }.getOrNull() ?: return response(
            "404 Not Found",
            "text/plain",
            "No such asset: $name".toByteArray(),
        )
        return response("200 OK", contentTypeFor(name), body)
    }

    private fun shellResponse(template: String, query: String): ByteArray {
        val file = query.split('&')
            .firstOrNull { it.startsWith("file=") }
            ?.let { decodeOrNull(it.removePrefix("file=")) }
            .orEmpty()
        val shell = runCatching {
            context.assets.open(template).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return response(
            "500 Internal Server Error",
            "text/plain",
            "viewer missing".toByteArray(),
        )
        val escaped = file.asJsStringBody()
        // The shells reach their assets and the file by absolute path, and every absolute
        // path on this server begins with the key. Their own scripts carry the nonce the
        // policy names; nothing the rendered Markdown brings in does.
        val nonce = newNonce()
        val body = shell
            .replace("__OW_FILE__", escaped)
            .replace("__OW_BASE__", "/${currentKey()}")
            .replace("__OW_NONCE__", nonce)
        return response(
            "200 OK",
            "text/html; charset=utf-8",
            body.toByteArray(),
            viewerPolicy(nonce),
        )
    }

    /**
     * The file at [path], named and read, or null when there is none — or when it is not
     * the canvas's to show. Nothing is served while no canvas is on screen.
     *
     * A folder is answered by the index page inside it, asked for as `site/` or `site`
     * alike. The trailing slash is dropped before the walk because the walk refuses an
     * empty name, and a link to a folder is written with the slash more often than not.
     */
    private suspend fun read(path: String): Pair<String, ByteArray>? {
        val canvas = board.showing.value ?: return null
        val wanted = path.trimEnd('/').ifEmpty { "index.html" }
        if (!canvas.contains(wanted)) return null
        val entry = workspace.resolve(wanted) ?: return null
        return if (entry.isDirectory) {
            read("$wanted/index.html")
        } else {
            workspace.readBytes(entry)?.let { wanted to it }
        }
    }

    private fun response(
        status: String,
        type: String,
        body: ByteArray,
        policy: String? = null,
    ): ByteArray {
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            // The canvas reloads on every write; yesterday's page must not outlive it.
            "Cache-Control: no-store\r\n" +
            // A file is what its extension says, never what a browser guesses it might be.
            "X-Content-Type-Options: nosniff\r\n" +
            policyHeaders(policy) +
            "Connection: close\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    /** The policy an HTML response carries, and no referrer with it; nothing for the rest. */
    private fun policyHeaders(policy: String?): String = if (policy == null) {
        ""
    } else {
        "Content-Security-Policy: $policy\r\nReferrer-Policy: no-referrer\r\n"
    }

    private fun contentTypeFor(path: String): String = when (path.substringAfterLast('.', "")) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "md", "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    internal companion object {
        /** Connections served at once; the rest wait in the backlog. See [scope]. */
        internal const val CONNECTIONS = 4
        private const val TAG = "CanvasServer"
        private const val VIEWER_PREFIX = "__ow__"
        private const val BACKLOG = 8
        private const val READ_TIMEOUT_MS = 10_000
        private const val KEY_BYTES = 16

        /**
         * What a page the model built may reach: its own folder and this server, nothing
         * off the phone. Inline scripts and eval stay allowed, because scripts are the
         * point of previewing a site and a built page is one file more often than not.
         */
        const val PAGE_POLICY = "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; " +
            "connect-src 'self'; form-action 'self'; base-uri 'none'; object-src 'none'"

        /**
         * What a viewer shell may do: run its own scripts and nothing the document brings.
         * Styles stay inline because the paginator writes them; scripts need the nonce.
         */
        fun viewerPolicy(nonce: String): String =
            "default-src 'self'; script-src 'self' 'nonce-$nonce'; " +
                "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; " +
                "font-src 'self' data:; connect-src 'self'; form-action 'none'; " +
                "frame-src 'none'; object-src 'none'; base-uri 'none'"

        /** 128 random bits as hex: unguessable, and nothing a URL needs escaping for. */
        private fun newKey(): String {
            val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** A nonce for one response: the same shape as the key, minted as often. */
        fun newNonce(): String = newKey()
    }
}

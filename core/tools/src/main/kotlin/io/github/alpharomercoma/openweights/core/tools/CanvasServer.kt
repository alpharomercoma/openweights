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
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * a chain of names the granted folder handed over, or it is nothing.
 */
@Singleton
class CanvasServer @Inject constructor(
    private val workspace: Workspace,
    @param:ApplicationContext private val context: Context,
) {

    private var socket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        scope.launch { accept(server) }
        return server.localPort
    }

    fun urlFor(path: String): String {
        val encoded = path.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "http://127.0.0.1:${port()}/$encoded"
    }

    /**
     * The URL that shows [path] through one of the bundled viewers.
     *
     * `doc` lays Markdown out as real A4 pages; `deck` as 16:9 slides. Both are plain
     * pages any browser on this phone can open, which is the point: the canvas in the app
     * and the tab in Chrome are the same rendering, byte for byte.
     */
    fun viewerUrlFor(kind: String, path: String): String =
        "http://127.0.0.1:${port()}/$VIEWER_PREFIX/$kind?file=" +
            URLEncoder.encode(path, "UTF-8")

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull()
            if (client != null) {
                scope.launch { client.use { serve(it) } }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val request = runCatching { reader.readLine() }.getOrNull() ?: return
        // Drain the headers; the answer does not depend on any of them.
        var header = runCatching { reader.readLine() }.getOrNull()
        while (!header.isNullOrEmpty()) {
            header = runCatching { reader.readLine() }.getOrNull()
        }
        val parts = request.split(' ')
        val out = client.getOutputStream()
        if (parts.size < 2 || parts[0] != "GET") {
            out.write(response("405 Method Not Allowed", "text/plain", "GET only".toByteArray()))
            return
        }
        val path = URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8").trimStart('/')
        val query = parts[1].substringAfter('?', "")
        val viewer = viewerResponse(path, query)
        if (viewer != null) {
            out.write(viewer)
            out.flush()
            return
        }
        val body = runBlocking { read(path) }
        if (body == null) {
            out.write(response("404 Not Found", "text/plain", "No such file: $path".toByteArray()))
        } else {
            out.write(response("200 OK", contentTypeFor(path), body))
        }
        out.flush()
    }

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
            ?.let { URLDecoder.decode(it.removePrefix("file="), "UTF-8") }
            .orEmpty()
        val shell = runCatching {
            context.assets.open(template).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return response(
            "500 Internal Server Error",
            "text/plain",
            "viewer missing".toByteArray(),
        )
        // Into a JS string literal, so everything that could end the literal is escaped.
        val escaped = file
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("<", "\\u003c")
        return response(
            "200 OK",
            "text/html; charset=utf-8",
            shell.replace("__OW_FILE__", escaped).toByteArray(),
        )
    }

    private suspend fun read(path: String): ByteArray? {
        val entry = workspace.resolve(path.ifEmpty { "index.html" }) ?: return null
        if (entry.isDirectory) return read(if (path.isEmpty()) "index.html" else "$path/index.html")
        return workspace.readBytes(entry)
    }

    private fun response(status: String, type: String, body: ByteArray): ByteArray {
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            // The canvas reloads on every write; yesterday's page must not outlive it.
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + body
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

    private companion object {
        const val VIEWER_PREFIX = "__ow__"
        const val BACKLOG = 8
        const val READ_TIMEOUT_MS = 10_000
    }
}

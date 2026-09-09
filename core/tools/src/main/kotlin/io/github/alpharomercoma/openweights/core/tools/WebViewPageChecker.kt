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

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads a page in a WebView nobody sees and keeps what the browser complained about.
 *
 * The same engine the canvas renders with, so what it reports is what the user's screen
 * would have hit. Uncaught exceptions and unhandled rejections arrive as console messages
 * at error level, which is why the console is the catch, and a resource the page named
 * and did not get is an error from the client. The page gets a moment after load for the
 * timers and handlers that fire on their own, and the whole look is bounded: a page that
 * never finishes loading is reported as nothing rather than held open.
 *
 * The WebView's egress rule is the canvas's: only the loopback address on the port the URL
 * names, or the page could ask a hidden browser to fetch what the visible one refuses.
 */
@Singleton
class WebViewPageChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PageChecker {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun check(url: String): PageReport? = withContext(Dispatchers.Main.immediate) {
        val port = Uri.parse(url).port
        val errors = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val finished = CompletableDeferred<Unit>()
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        val where = message.sourceId().substringAfterLast('/').substringBefore('?')
                        errors += "${message.message()} ($where:${message.lineNumber()})"
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (request.url.staysOnDevice(port)) return null
                    blocked += request.url.host ?: request.url.toString()
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        HTTP_FORBIDDEN,
                        "Blocked",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = !request.url.staysOnDevice(port)

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        finished.completeExceptionally(
                            IllegalStateException(error.description.toString()),
                        )
                    } else {
                        missing += request.url.lastPathSegment ?: request.url.toString()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    response: WebResourceResponse,
                ) {
                    // The 403 this same client answered an off-device request with is
                    // already in `blocked`, under the host rather than a file name.
                    if (!request.isForMainFrame && request.url.staysOnDevice(port)) {
                        missing +=
                            request.url.lastPathSegment ?: request.url.toString()
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    finished.complete(Unit)
                }
            }
        }
        try {
            web.loadUrl(url)
            val loaded =
                withTimeoutOrNull(LOAD_TIMEOUT_MS) { runCatching { finished.await() }.isSuccess }
            if (loaded != true) return@withContext null
            delay(SETTLE_MS)
            PageReport(
                errors = errors.distinct(),
                missing = missing.distinct(),
                blocked = blocked.distinct(),
            )
        } finally {
            web.stopLoading()
            web.destroy()
        }
    }

    private fun Uri.staysOnDevice(port: Int): Boolean = when (scheme?.lowercase()) {
        "http", "https" -> host == LOOPBACK && this.port == port
        "data", "blob", "about" -> true
        else -> false
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val HTTP_FORBIDDEN = 403

        /** A page over loopback loads in tens of milliseconds; this is for one that never does. */
        const val LOAD_TIMEOUT_MS = 8_000L

        /** Room for a setTimeout or an interval's first tick, the usual place a script fails. */
        const val SETTLE_MS = 1_200L
    }
}

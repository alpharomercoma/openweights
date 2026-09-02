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

import android.annotation.SuppressLint
import android.content.Intent
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.tools.CanvasKind
import java.io.ByteArrayInputStream

/**
 * Where the model's work appears: a site rendered live, or a document as a real page.
 *
 * The screen is a window onto files in the shared folder. Every save the model makes
 * bumps the canvas revision, and the revision is a key here, so the WebView reloads and
 * the document re-renders while the user watches — that is the whole feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasScreen(onBack: () -> Unit, viewModel: CanvasViewModel = hiltViewModel()) {
    val showing by viewModel.showing.collectAsState()
    val canvas = showing

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = canvas?.entry?.substringAfterLast('/')
                            ?: stringResource(R.string.canvas_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (canvas != null) {
                        val context = LocalContext.current
                        val url = viewModel.viewerUrlFor(canvas)
                        IconButton(
                            onClick = {
                                // The same loopback URL the WebView reads; any browser on
                                // this phone can open it while the app is running. True
                                // for all three kinds now: a document and a deck are
                                // pages the same server serves.
                                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.OpenInBrowser,
                                contentDescription = stringResource(R.string.open_in_browser),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (canvas?.kind) {
            null -> Text(
                text = stringResource(R.string.canvas_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(24.dp),
            )

            CanvasKind.SITE -> Site(
                url = viewModel.viewerUrlFor(canvas),
                revision = canvas.revision,
                modifier = Modifier.padding(padding).fillMaxSize(),
            )

            // Both Markdown kinds are pages too: the bundled viewers lay a document out
            // as real A4 pages and a deck as 16:9 slides, and the same URL opens in any
            // browser on the phone. One rendering, two windows.
            CanvasKind.DOCUMENT -> Site(
                url = viewModel.viewerUrlFor(canvas),
                revision = canvas.revision,
                zoomable = true,
                modifier = Modifier.padding(padding).fillMaxSize(),
            )

            CanvasKind.SLIDES -> Site(
                url = viewModel.viewerUrlFor(canvas),
                revision = canvas.revision,
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Site(
    url: String,
    revision: Int,
    modifier: Modifier = Modifier,
    /** Pinch zoom, for the document viewer: an A4 page reads like a PDF, zoom included. */
    zoomable: Boolean = false,
) {
    val view = remember { mutableListOf<WebView>() }
    // System Back walks the site's own history first, the way any browser does; only
    // when there is nowhere left to go back to does it fall through and close the
    // canvas. The toolbar arrow always closes — two exits, two meanings.
    BackHandler(enabled = view.firstOrNull()?.canGoBack() == true) {
        view.firstOrNull()?.goBack()
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // The page is the model's own work served from the user's folder over
                // loopback; scripts are the point of previewing a site.
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Honour the page's own viewport meta, the way every real browser does.
                // Without this the deck viewer laid out at its stage width — 1280 CSS px —
                // so its fit-to-screen scale computed to 1 and the slides rendered at
                // projector size on a phone.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                if (zoomable) {
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                }
                // The server starts in this same process a moment before the first load,
                // and on a cold start the connect can lose that race once. A failed load
                // otherwise sits on an error page forever — nothing bumps the revision —
                // so the main frame retries, briefly and a bounded number of times.
                webViewClient = object : WebViewClient() {
                    private var retries = 0

                    // The WebView's half of the server's policy: nothing off the phone,
                    // whether the page asks with a fetch, an image, a form or by leaving.
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        if (request.url.staysOnDevice()) return null
                        Log.w("OpenWeights", "canvas refused a request to ${request.url.host}")
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
                    ): Boolean {
                        if (request.url.staysOnDevice()) return false
                        Log.w("OpenWeights", "canvas refused to leave for ${request.url.host}")
                        return true
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame && retries < MAX_LOAD_RETRIES) {
                            retries++
                            view.postDelayed({ view.reload() }, RETRY_DELAY_MS)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        retries = 0
                    }
                }
                view += this
                loadUrl(url)
            }
        },
        update = { web -> if (web.url != url) web.loadUrl(url) },
    )
    // A new revision is a saved file under the served folder: reload, keeping scroll.
    LaunchedEffect(revision, url) {
        view.firstOrNull()?.reload()
    }
}

private const val MAX_LOAD_RETRIES = 4
private const val RETRY_DELAY_MS = 600L
private const val HTTP_FORBIDDEN = 403

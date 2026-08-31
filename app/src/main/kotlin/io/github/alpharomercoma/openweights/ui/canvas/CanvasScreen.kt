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
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.alpharomercoma.openweights.core.designsystem.component.MarkdownText
import io.github.alpharomercoma.openweights.core.tools.Canvas
import io.github.alpharomercoma.openweights.core.tools.CanvasKind

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
                    if (canvas?.kind == CanvasKind.SITE) {
                        val context = LocalContext.current
                        val url = viewModel.urlFor(canvas)
                        IconButton(
                            onClick = {
                                // The same loopback URL the WebView reads; any browser on
                                // this phone can open it while the app is running.
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
                url = viewModel.urlFor(canvas),
                revision = canvas.revision,
                modifier = Modifier.padding(padding).fillMaxSize(),
            )

            CanvasKind.DOCUMENT -> Document(
                canvas = canvas,
                viewModel = viewModel,
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Site(url: String, revision: Int, modifier: Modifier = Modifier) {
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
                webViewClient = WebViewClient()
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

@Composable
private fun Document(canvas: Canvas, viewModel: CanvasViewModel, modifier: Modifier = Modifier) {
    val text by viewModel.documentText.collectAsState()
    LaunchedEffect(canvas.entry, canvas.revision) {
        viewModel.refreshDocument(canvas)
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        MarkdownText(content = text, modifier = Modifier.fillMaxWidth())
    }
}

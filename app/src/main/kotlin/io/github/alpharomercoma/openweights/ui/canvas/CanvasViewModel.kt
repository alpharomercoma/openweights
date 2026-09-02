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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.tools.Canvas
import io.github.alpharomercoma.openweights.core.tools.CanvasBoard
import io.github.alpharomercoma.openweights.core.tools.CanvasKind
import io.github.alpharomercoma.openweights.core.tools.CanvasServer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The canvas as the screen reads it: what to show, where to load it from, when to reload.
 *
 * Thin on purpose. The state lives in [CanvasBoard], written by the tools as the model
 * works; this only turns a [Canvas] into a URL for the WebView or text for the Markdown
 * renderer, and re-reads the document when the revision moves.
 */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    private val board: CanvasBoard,
    private val server: CanvasServer,
) : ViewModel() {

    val showing: StateFlow<Canvas?> = board.showing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), board.showing.value)

    /**
     * The loopback URL that renders [canvas] — which any browser on this phone can open.
     *
     * A site is served as itself; the Markdown kinds go through the bundled viewers,
     * which lay a document out as A4 pages and a deck as 16:9 slides. The app's WebView
     * and a Chrome tab read the same URL, so what the user shares is what they saw.
     */
    fun viewerUrlFor(canvas: Canvas): String = when (canvas.kind) {
        CanvasKind.SITE -> server.urlFor(canvas.entry)
        CanvasKind.DOCUMENT -> server.viewerUrlFor("doc", canvas.entry)
        CanvasKind.SLIDES -> server.viewerUrlFor("deck", canvas.entry)
    }

    /**
     * Closes the canvas and the server behind it. A URL that was open in a browser tab
     * stops answering, which is the point: the folder is served while it is on screen.
     */
    fun dismiss() {
        board.dismiss()
        server.stop()
    }

    /**
     * Leaving the screen stops the server, whatever the board still holds.
     *
     * The server's own contract is that a URL learned while the canvas was open answers
     * nothing once it is closed, and until this the back arrow closed the screen without
     * ever telling the server, so a tab opened from "open in browser" kept reading the
     * folder for as long as the process lived. The canvas itself is kept: the next open
     * asks for a port again and gets a fresh server under a fresh key.
     */
    override fun onCleared() {
        server.stop()
    }
}

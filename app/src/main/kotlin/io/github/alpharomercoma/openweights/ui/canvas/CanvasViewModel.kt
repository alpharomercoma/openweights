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
import io.github.alpharomercoma.openweights.core.tools.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val workspace: Workspace,
) : ViewModel() {

    val showing: StateFlow<Canvas?> = board.showing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), board.showing.value)

    private val text = MutableStateFlow("")

    /** The document's current text, for [CanvasKind.DOCUMENT]; empty for a site. */
    val documentText: StateFlow<String> = text.asStateFlow()

    /** The loopback URL for [canvas], which any browser on this phone can open. */
    fun urlFor(canvas: Canvas): String = server.urlFor(canvas.entry)

    /** Re-reads the document; called when the entry or revision changes. */
    fun refreshDocument(canvas: Canvas) {
        if (canvas.kind != CanvasKind.DOCUMENT) return
        viewModelScope.launch {
            val entry = workspace.resolve(canvas.entry) ?: return@launch
            text.value = workspace.readBytes(entry)?.toString(Charsets.UTF_8).orEmpty()
        }
    }

    fun dismiss() = board.dismiss()
}

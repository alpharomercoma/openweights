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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What kind of thing the canvas is showing. */
enum class CanvasKind { SITE, DOCUMENT, SLIDES }

/**
 * Something the model has put on screen: a site being built, or a document being written.
 *
 * @param entry the file the canvas opens: an HTML page, or a Markdown document.
 * @param root the folder the site is served from, so its own CSS and scripts resolve.
 * @param revision bumped on every write under [root], which is what makes the canvas live —
 * the screen reloads on the bump, so the user watches the site change as the model works.
 */
data class Canvas(
    val kind: CanvasKind,
    val entry: String,
    val root: String,
    val revision: Int = 0,
    /**
     * Which call to a show tool produced this. The chat screen opens the canvas when this
     * moves and only then, so the model showing something surfaces it once — the user can
     * step back to the conversation without the screen forcing itself over them again.
     */
    val generation: Int = 0,
) {
    /**
     * Whether [path] is part of what this canvas shows.
     *
     * One answer for two questions — does a write here repaint the screen, and may the
     * server hand this file to the page — because a page reading a file it could not have
     * repainted from is exactly the read the server exists to refuse.
     *
     * A site is its entry and everything under the folder it is served from, since that is
     * where its own CSS and scripts live; a root of "" is a site at the top of the shared
     * folder, which is the whole folder. The folder itself counts too, so that asking for
     * it by name is answered with its index page the way every other server answers. A
     * document or a deck is one Markdown file rendered by a viewer the APK supplies, so it
     * is the entry and nothing else: the notes beside a report are not part of the report.
     */
    fun contains(path: String): Boolean = when (kind) {
        CanvasKind.SITE ->
            root.isEmpty() || path == entry || path == root || path.startsWith("$root/")
        CanvasKind.DOCUMENT, CanvasKind.SLIDES -> path == entry
    }
}

/**
 * The one canvas on screen, owned here the way [AskBoard] owns the pending question.
 *
 * A board rather than a tool result, because the whole point is that it outlives the turn:
 * the model keeps editing files across rounds and the screen keeps up, which no string
 * handed back into the transcript could do.
 */
@Singleton
class CanvasBoard @Inject constructor() {
    private val current = MutableStateFlow<Canvas?>(null)

    val showing: StateFlow<Canvas?> = current.asStateFlow()

    fun show(kind: CanvasKind, entry: String, root: String) {
        current.value = Canvas(
            kind = kind,
            entry = entry,
            root = root,
            generation = (current.value?.generation ?: 0) + 1,
        )
    }

    /** Called after every workspace write; a write under the root is a visible change. */
    fun changed(path: String) {
        val canvas = current.value ?: return
        if (canvas.contains(path)) current.value = canvas.copy(revision = canvas.revision + 1)
    }

    fun dismiss() {
        current.value = null
    }
}

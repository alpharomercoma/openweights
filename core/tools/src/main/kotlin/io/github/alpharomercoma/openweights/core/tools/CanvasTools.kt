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

import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts a page the model has written on screen, live.
 *
 * The other half of the write tool: the model saves HTML, CSS and JavaScript into the
 * shared folder, calls this once, and from then on every further save repaints the screen
 * the user is looking at. The site is served over loopback, so the user can also open the
 * same URL in a real browser — that is what makes this development rather than a demo.
 */
@Singleton
class ShowWebsiteTool @Inject constructor(
    private val workspace: Workspace,
    private val board: CanvasBoard,
) : Tool {
    override val definition = ToolDefinition(
        name = "show_website",
        description = "Show an HTML page you saved to the user, rendered live. Point it " +
            "at the page's path, like site/index.html. Its folder is served whole, so " +
            "relative links to CSS, scripts and images in that folder work. Call it once " +
            "after the first save; later saves update the page on screen by themselves.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "The HTML file to show, like site/index.html"
                }
              },
              "required": ["path"]
            }
        """.trimIndent(),
    )

    override val isAvailable: Boolean get() = workspace.isReady

    override val chains: Boolean = true

    override val builds: Boolean = true

    override suspend fun run(call: ToolCall): String = execute(call).text

    override suspend fun execute(call: ToolCall): ToolExecution {
        workspace.refusal()?.let { return it }
        val path = call.argument("path", "file", "page")
            ?: return ToolExecution.rejected("No path was given. Which HTML file?")
        return when (val entry = workspace.resolve(path)) {
            null -> ToolExecution.rejected("There is no $path. Save the page first.")
            else -> show(path, entry)
        }
    }

    private suspend fun show(path: String, entry: Entry): ToolExecution {
        if (entry.isDirectory) {
            val index = workspace.resolve("$path/index.html")
                ?: return ToolExecution.rejected(
                    "$path is a folder with no index.html in it. Point at the HTML file.",
                )
            board.show(CanvasKind.SITE, index.path, path)
            return ToolExecution("Showing ${index.path}. Saves under $path update it live.")
        }
        val root = path.substringBeforeLast('/', "")
        board.show(CanvasKind.SITE, path, root)
        return ToolExecution(
            "Showing $path to the user. Further saves under " +
                (root.ifEmpty { "the folder" }) + " update the page live.",
        )
    }
}

/**
 * Puts a document the model is writing on screen, rendered, live.
 *
 * The same canvas as [ShowWebsiteTool] with Markdown instead of a site: the model writes
 * report.md, shows it, and keeps editing while the user watches the rendered page grow.
 */
@Singleton
class ShowDocumentTool @Inject constructor(
    private val workspace: Workspace,
    private val board: CanvasBoard,
) : Tool {
    override val definition = ToolDefinition(
        name = "show_document",
        description = "Show a Markdown document you saved to the user, laid out as real " +
            "A4 pages. Point it at the file, like notes/report.md. Call it once after " +
            "the first save; later saves with replace update the pages live.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "The Markdown file to show, like notes/report.md"
                }
              },
              "required": ["path"]
            }
        """.trimIndent(),
    )

    override val isAvailable: Boolean get() = workspace.isReady

    override val chains: Boolean = true

    override val builds: Boolean = true

    override suspend fun run(call: ToolCall): String = execute(call).text

    override suspend fun execute(call: ToolCall): ToolExecution {
        workspace.refusal()?.let { return it }
        val path = call.argument("path", "file", "document")
            ?: return ToolExecution.rejected("No path was given. Which document?")
        val entry = workspace.resolve(path)
        return when {
            entry == null -> ToolExecution.rejected("There is no $path. Save the document first.")
            entry.isDirectory -> ToolExecution.rejected("$path is a folder, not a document.")
            else -> {
                board.show(CanvasKind.DOCUMENT, path, path.substringBeforeLast('/', ""))
                ToolExecution("Showing $path to the user. Saves with replace update it live.")
            }
        }
    }
}

/**
 * Puts a slide deck the model is writing on screen, one slide at a time, live.
 *
 * The third canvas kind, and deliberately the same medium as the second: a deck is a
 * Markdown file whose slides are separated by --- lines, which is the convention every
 * slide-from-Markdown tool (Marp, reveal.js, Slidev) settled on and the one small models
 * already know. One file means the iteration loop is the document's: the model saves with
 * replace, the canvas bumps, and the deck on screen updates without losing the reader's
 * place.
 */
@Singleton
class ShowSlidesTool @Inject constructor(
    private val workspace: Workspace,
    private val board: CanvasBoard,
) : Tool {
    override val definition = ToolDefinition(
        name = "show_slides",
        description = "Show a Markdown file as a 16:9 slide deck the user swipes " +
            "through. Separate slides with a line containing only ---. Call it once " +
            "after the first save; later saves with replace update the deck live.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "The Markdown deck to show, like talk/slides.md"
                }
              },
              "required": ["path"]
            }
        """.trimIndent(),
    )

    override val isAvailable: Boolean get() = workspace.isReady

    override val chains: Boolean = true

    override val builds: Boolean = true

    override suspend fun run(call: ToolCall): String = execute(call).text

    override suspend fun execute(call: ToolCall): ToolExecution {
        workspace.refusal()?.let { return it }
        val path = call.argument("path", "file", "deck")
            ?: return ToolExecution.rejected("No path was given. Which deck?")
        val entry = workspace.resolve(path)
        return when {
            entry == null -> ToolExecution.rejected("There is no $path. Save the deck first.")
            entry.isDirectory -> ToolExecution.rejected("$path is a folder, not a deck.")
            else -> {
                board.show(CanvasKind.SLIDES, path, path.substringBeforeLast('/', ""))
                ToolExecution("Showing $path as slides. Saves with replace update it live.")
            }
        }
    }
}

/**
 * Removes a file or folder from the shared folder.
 *
 * The missing quarter of create, read and update. Asks before touching anything the user
 * put there; what the session itself created goes quietly, because deleting a scratch
 * file the model wrote a minute ago is housekeeping, not destruction.
 */
@Singleton
class DeleteFileTool @Inject constructor(
    private val workspace: Workspace,
    private val artifacts: SessionArtifacts,
) : Tool {
    override val definition = ToolDefinition(
        name = "delete_file",
        description = "Delete a file or folder from the folder the user shared. Use it " +
            "to clean up files you created that are no longer needed. Deleting a folder " +
            "deletes everything in it.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "What to delete, like scratch/attempt1.js"
                }
              },
              "required": ["path"]
            }
        """.trimIndent(),
    )

    override val isAvailable: Boolean get() = workspace.isReady && workspace.acceptsNewFiles

    override val chains: Boolean = true

    override val writesDurableData: Boolean = true

    /** The user's files ask in every mode; the session's own scratch does not. */
    override fun asksInAuto(call: ToolCall): Boolean =
        call.argument("path", "file")?.let { !artifacts.isOwn(it) } ?: true

    override suspend fun run(call: ToolCall): String = execute(call).text

    override suspend fun execute(call: ToolCall): ToolExecution {
        workspace.refusal()?.let { return it }
        val path = call.argument("path", "file")
            ?: return ToolExecution.rejected("No path was given. What should be deleted?")
        return workspace.delete(path)
    }
}

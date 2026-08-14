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
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds files in the shared folder by name, and optionally by what is inside them.
 *
 * Every bound here is doing real work rather than being tidy. Walking a folder through the
 * Storage Access Framework costs a round trip per directory, and reading a file to see
 * whether a word is in it costs one per file, so an unbounded search of somebody's Documents
 * folder is tens of seconds inside a turn that is already slow. What it gives back when it
 * runs out is a partial answer that says it is partial, because a truncated list presented
 * as a complete one is how a model concludes a file does not exist.
 */
@Singleton
class SearchFilesTool @Inject constructor(private val workspace: Workspace) : Tool {
    override val definition = ToolDefinition(
        name = "search_files",
        description = "Find files in the folder the user shared. Match names with a " +
            "pattern like *.md, and optionally give text to look for inside them.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "pattern": {
                  "type": "string",
                  "description": "A file name or a pattern such as *.md or notes*"
                },
                "contains": {
                  "type": "string",
                  "description": "Optional text that must appear inside the file"
                }
              },
              "required": ["pattern"]
            }
        """.trimIndent(),
    )

    override val isAvailable: Boolean get() = workspace.isReady

    override val chains: Boolean = true

    override suspend fun run(call: ToolCall): String =
        workspace.unavailable().ifEmpty { search(call) }

    private suspend fun search(call: ToolCall): String {
        val pattern = call.argument("pattern", "name", "glob", "query")
            ?: return "No pattern was given. Call search_files again with a pattern like *.md."
        val contains = call.argument("contains", "text", "containing")
        val hunt = Hunt(workspace, pattern.asNameMatcher(), contains)

        val finished = withTimeoutOrNull(DEADLINE_MILLIS) { hunt.walk(workspace.root(), 0) }
        return hunt.report(ranOut = finished == null)
    }
}

/**
 * Reads part of a file in the shared folder.
 *
 * A window rather than the whole thing, because the window it has to fit into is a couple of
 * thousand tokens shared with the conversation that asked. Line numbers are deliberately not
 * added: they earn their place in a harness that edits by line range, and this one does not,
 * so they would be a tax charged on every line for nothing.
 */
@Singleton
class ReadFileTool @Inject constructor(private val workspace: Workspace) : Tool {
    override val definition = ToolDefinition(
        name = "read_file",
        description = "Read the text of a file in the folder the user shared. Use the path " +
            "exactly as search_files gave it.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "The file's path inside the shared folder, like notes/todo.md"
                },
                "offset": {
                  "type": "integer",
                  "description": "Characters to skip, to read further into a long file"
                }
              },
              "required": ["path"]
            }
        """.trimIndent(),
    )

    override val isAvailable: Boolean get() = workspace.isReady

    override val chains: Boolean = true

    override suspend fun run(call: ToolCall): String =
        workspace.unavailable().ifEmpty { read(call) }

    private suspend fun read(call: ToolCall): String {
        val path = call.argument("path", "file", "name")
            ?: return "No path was given. Call read_file again with a path."
        val entry = workspace.resolve(path)
            ?: return "There is no file at $path in the shared folder. Use search_files first."
        val skip = call.argument("offset", "start", "skip")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return window(entry, path, skip)
    }

    private suspend fun window(entry: Entry, path: String, skip: Int): String {
        if (entry.isDirectory) {
            return "$path is a folder, not a file. Use search_files to see what is in it."
        }
        val text = workspace.readText(entry, skip, WINDOW_CHARS)
            ?: return "$path could not be read. It may not be text."
        return text.ifEmpty { "$path has nothing more to read from character $skip." }
    }
}

/**
 * Makes a new file in the shared folder.
 *
 * Creation only, and that is the design rather than a first version. Overwriting means the
 * model has to hold the whole existing file and reproduce it, which at this size means a
 * read that gets truncated to fit the context and then written back as if it were whole:
 * the file loses its second half to a tool that reported success. Refusing a path that
 * already exists removes that entire class of outcome, and leaves the thing people actually
 * want on a phone, which is to say "save that as a note".
 */
@Singleton
class WriteFileTool @Inject constructor(private val workspace: Workspace) : Tool {
    override val definition = ToolDefinition(
        name = "write_file",
        description = "Save a new file into the folder the user shared. It will not " +
            "replace a file that already exists.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Where to save it, like notes/summary.md"
                },
                "content": {
                  "type": "string",
                  "description": "The whole text of the file"
                }
              },
              "required": ["path", "content"]
            }
        """.trimIndent(),
    )

    override val isAvailable: Boolean
        get() = workspace.isReady && workspace.acceptsNewFiles

    override val chains: Boolean = true

    override suspend fun run(call: ToolCall): String =
        workspace.unavailable().ifEmpty { create(call) }

    private suspend fun create(call: ToolCall): String {
        val path = call.argument("path", "file", "name")
            ?: return "No path was given. Call write_file again with a path."
        val content = call.textArgument("content", "text", "body")
            ?: return "No content was given. Call write_file again with the file's text."
        if (content.length > MAX_WRITE_CHARS) {
            return "That is longer than this tool writes at once. Keep it under " +
                "$MAX_WRITE_CHARS characters, or save it in parts."
        }
        return workspace.put(path, content)
    }
}

/**
 * Turns a pattern the model wrote into a test on a file's name.
 *
 * Only `*` and `?`, because that is what people and models write when asked for a pattern,
 * and everything else in the string is taken literally. A name with no wildcard in it at all
 * matches on being contained, since a model asked to find "budget" means the spreadsheet
 * rather than a file called exactly that.
 */
private fun String.asNameMatcher(): (String) -> Boolean {
    if (!contains('*') && !contains('?')) {
        val needle = this
        return { it.contains(needle, ignoreCase = true) }
    }
    val expression = split('*', '?').joinToString(".*") { Regex.escape(it) }
    val regex = runCatching { Regex("^$expression$", RegexOption.IGNORE_CASE) }.getOrNull()
    return { name -> regex?.matches(name) ?: false }
}

/** What one read_file call hands back, well inside what the tool budget will keep. */
private const val WINDOW_CHARS = 1_500

/**
 * The most a single write may carry.
 *
 * Not a storage limit. At the decode rate measured on a Snapdragon 8 Elite, two thousand
 * characters is already the better part of a minute of the model typing, and a tool nobody
 * will sit through is not a working tool.
 */
private const val MAX_WRITE_CHARS = 2_000

/** Long enough for a real folder, short enough that a turn does not appear to have hung. */
private const val DEADLINE_MILLIS = 6_000L

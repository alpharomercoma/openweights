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
        // The "not for" half is load-bearing and was measured twice. Removing it to save
        // tokens put "read that file for me" back to calling this tool with a guessed
        // pattern instead of asking which file, so it went back in.
        description = "Find files in the folder the user shared. Match names with a " +
            "pattern like *.md, and optionally give text to look for inside them. Not for " +
            "reading a file whose path you have. If nothing was named to look for, ask " +
            "rather than guess.",
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

    // Paths are private data too, and file names come from outside the model's trust
    // boundary. `contains` also reads contents even though it only returns matching paths.
    override val readsPrivateData: Boolean = true
    override val returnsUntrustedText: Boolean = true

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
            "exactly as search_files gave it. If no path is known, ask which file rather " +
            "than inventing one.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "The file's path inside the shared folder, like notes/todo.md. Text only"
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

    /**
     * The one tool here that hands the model a stranger's words.
     *
     * search_files reports paths and never contents, and write_file only carries text the
     * model already had, so this is the single point where something written by somebody
     * else enters the turn.
     */
    override val returnsUntrustedText: Boolean = true

    /** And by the same argument, the single point where the user's own text enters it. */
    override val readsPrivateData: Boolean = true

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

    // Guard clauses keep each incompatible file shape next to its user-facing explanation.
    @Suppress("ReturnCount")
    private suspend fun window(entry: Entry, path: String, skip: Int): String {
        if (entry.isDirectory) {
            return "$path is a folder, not a file. Use search_files to see what is in it."
        }
        // A picture is not a failed text file, and saying so is the difference between a
        // model that asks for it and one that gives up.
        //
        // This tool reads text and only text. A model that can see, which here means one
        // loaded with its projector, still cannot be shown anything through a tool result:
        // a result is a string, and there is no path from a tool back into the prompt for
        // an image. So the honest answer names the file, says what it is, and says the one
        // thing that would work, which is the user attaching it to a message.
        entry.mediaType.substringBefore('/').let { family ->
            if (family in NOT_TEXT) {
                return "$path is $family rather than text, so read_file cannot show it to " +
                    "you. Ask the user to attach it to a message if you need to see it."
            }
        }

        // One character past the window, so whether anything follows is a fact rather than an
        // inference from having filled the buffer exactly.
        val text = workspace.readText(entry, skip, WINDOW_CHARS + 1)
            ?: return "$path could not be read. It may not be text."
        if (text.isEmpty()) return "$path has nothing more to read from character $skip."
        return text.take(WINDOW_CHARS) + rest(read = text.length, skip = skip)
    }

    /**
     * What to say when the window stopped before the file did.
     *
     * A window presented as a whole file is how a model concludes a document does not mention
     * something, and answers confidently out of the first fifteen hundred characters of it.
     * The offset is worked out here rather than left to the model, which at this size is the
     * difference between a second page and a second copy of the first one.
     */
    private fun rest(read: Int, skip: Int): String {
        if (read <= WINDOW_CHARS) return ""
        return "\n\n[Cut here: this is $WINDOW_CHARS characters starting at $skip, and the " +
            "file goes on. To read the next part, call read_file again with the same path " +
            "and offset ${skip + WINDOW_CHARS}.]"
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
        description = "Save a file into the folder the user shared. Pass replace to " +
            "overwrite one that exists, which is how to fix and re-save a script. If no " +
            "path is given, ask where.",
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
                },
                "replace": {
                  "type": "boolean",
                  "description": "True to overwrite an existing file"
                }
              },
              "required": ["path", "content"]
            }
        """.trimIndent(),
    )

    override val isAvailable: Boolean
        get() = workspace.isReady && workspace.acceptsNewFiles

    override val chains: Boolean = true

    /**
     * A write changes durable user state. It must remain an explicit capability even when
     * the request came after an untrusted page or file: otherwise prompt injection can turn
     * "save this" into an unattended workspace mutation. Replace is still called out in
     * [asksInAuto] for callers that inspect the per-call reason.
     */
    override val alwaysAsks: Boolean = true

    override fun asksInAuto(call: ToolCall): Boolean = call.flag("replace", "overwrite")

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
        return workspace.put(path, content, replace = call.flag("replace", "overwrite"))
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
/**
 * Turns a search pattern into a predicate on a file name.
 *
 * Internal rather than private so it can be tested directly. It was untested, and it was
 * wrong: both wildcards behaved as `*`.
 */
internal fun String.asNameMatcher(): (String) -> Boolean {
    if (!contains('*') && !contains('?')) {
        val needle = this
        return { it.contains(needle, ignoreCase = true) }
    }
    val pattern = lowercase()
    return { name -> pattern.globMatches(name.lowercase()) }
}

/** Linear-time `*`/`?` glob matching; regex backtracking lets a model pin the UI thread. */
private fun String.globMatches(value: String): Boolean {
    var patternAt = 0
    var valueAt = 0
    var lastStar = -1
    var afterStar = -1
    while (valueAt < value.length) {
        when {
            patternAt < length && (this[patternAt] == '?' || this[patternAt] == value[valueAt]) -> {
                patternAt++
                valueAt++
            }
            patternAt < length && this[patternAt] == '*' -> {
                lastStar = patternAt++
                afterStar = valueAt
            }
            lastStar >= 0 -> {
                patternAt = lastStar + 1
                valueAt = ++afterStar
            }
            else -> return false
        }
    }
    while (patternAt < length && this[patternAt] == '*') patternAt++
    return patternAt == length
}

/** What one read_file call hands back, well inside what the tool budget will keep. */
/** Media families a text tool has nothing useful to say about. */
private val NOT_TEXT = setOf("image", "audio", "video")

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

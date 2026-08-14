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

/**
 * One run of a search, and everything it is allowed to spend.
 *
 * A class rather than a function because the budget has to be carried down the walk and
 * reported back up: what a bounded search gives back is not just the matches but the fact
 * that it stopped. A list that ran out of time, handed over as though it were the whole
 * answer, is how a model concludes a file is not there and tells the user so.
 *
 * The walk is breadth-limited by depth rather than truly breadth-first, which is the cheaper
 * shape here: each directory costs a round trip to the provider whichever order they are
 * visited in, and depth is what stops a search wandering into a backup folder.
 */
internal class Hunt(
    private val workspace: Workspace,
    private val matches: (String) -> Boolean,
    private val contains: String?,
) {
    private val found = mutableListOf<String>()
    private var visited = 0
    private var stoppedEarly = false

    suspend fun walk(entries: List<Entry>, depth: Int) {
        if (depth > MAX_DEPTH) {
            stoppedEarly = true
            return
        }
        for (entry in entries) {
            if (spent()) return
            if (entry.isDirectory) walk(workspace.children(entry), depth + 1) else consider(entry)
        }
    }

    /** Whether there is any budget left, remembering that there was not if there is not. */
    private fun spent(): Boolean {
        val out = found.size >= MAX_RESULTS || visited >= MAX_VISITS
        if (out) stoppedEarly = true
        return out
    }

    private suspend fun consider(entry: Entry) {
        visited++
        if (!matches(entry.name)) return
        if (contains == null) {
            found += entry.path
            return
        }
        if (!entry.readableAsText() || entry.sizeBytes > MAX_PEEK_BYTES) return
        val text = workspace.readText(entry, 0, PEEK_CHARS).orEmpty()
        if (text.contains(contains, ignoreCase = true)) found += entry.path
    }

    /**
     * What the model is told, including what was not looked at.
     *
     * [ranOut] is the deadline having fired rather than a budget being used up, which is a
     * different sentence: one means the folder is big, the other means it was slow.
     */
    fun report(ranOut: Boolean): String {
        val cut = stoppedEarly || ranOut
        if (found.isEmpty()) {
            return if (cut) {
                "Nothing matched in the part of the folder that was searched, and there was " +
                    "more of it than could be looked at. Try a narrower pattern."
            } else {
                "Nothing in the shared folder matched."
            }
        }
        val header = if (cut) {
            "The first ${found.size}, and there may be more that were not reached:"
        } else {
            "${found.size} found:"
        }
        return (listOf(header) + found).joinToString("\n")
    }
}

/**
 * Whether a file is worth opening to look inside.
 *
 * Decided from the type the provider declares, and from the name only when it declares
 * nothing useful. Sniffing the first bytes for a zero was the other candidate and it is
 * worse: it reads UTF-16, which begins with one, as binary, and it lets through every
 * compressed format that happens to start with printable bytes.
 */
private fun Entry.readableAsText(): Boolean = when {
    mediaType.startsWith("text/") -> true
    mediaType in TEXTUAL_TYPES -> true
    mediaType.isEmpty() || mediaType == "application/octet-stream" ->
        name.substringAfterLast('.', "").lowercase() in TEXTUAL_EXTENSIONS
    else -> false
}

private val TEXTUAL_TYPES = setOf(
    "application/json",
    "application/xml",
    "application/xhtml+xml",
    "application/javascript",
    "application/x-yaml",
)

private val TEXTUAL_EXTENSIONS = setOf(
    "md", "txt", "json", "xml", "yaml", "yml", "csv", "log", "kt", "java", "py", "js", "ts",
    "html", "css", "sh", "toml", "ini", "cfg", "properties", "gradle",
)

/** Enough of a file to tell whether a word is in it, without reading a book to find out. */
private const val PEEK_CHARS = 4_000

/** Files bigger than this are not the note anybody was looking for. */
private const val MAX_PEEK_BYTES = 1L * 1024 * 1024

/** More than a small model can use in one answer, and more than the budget would keep. */
private const val MAX_RESULTS = 10

/** The real bound on how long a search takes, since each file may cost a round trip. */
private const val MAX_VISITS = 400

/** Past this a folder is an archive, and a search of it is a different feature. */
private const val MAX_DEPTH = 6

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

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Reader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills as much of [buffer] as the stream will part with.
 *
 * One read rarely returns everything asked for, and a single call would quietly return a
 * fraction of the window and look like a short file.
 */
/**
 * What to tell the provider a new file is, worked out from its name.
 *
 * A guess, and a deliberately dull one. Providers use this to decide the icon and sometimes
 * whether to accept the file at all, and plain text is the reading least likely to be
 * refused by any of them.
 */
private fun mediaTypeFor(name: String): String = when (name.substringAfterLast('.', "")) {
    "json" -> "application/json"
    "csv" -> "text/csv"
    "html", "htm" -> "text/html"
    else -> "text/plain"
}

internal fun Reader.readAsMuchAs(buffer: CharArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val read = read(buffer, filled, buffer.size - filled)
        if (read < 0) break
        filled += read
    }
    return filled
}

/**
 * Advances exactly [count] characters unless the stream ends.
 *
 * [Reader.skip] is explicitly allowed to advance fewer characters than requested. Treating
 * one call as exact repeats part of the previous page with providers that use short skips.
 * A zero skip falls back to one read so a legal but unhelpful provider cannot spin forever.
 */
@Suppress("LoopWithTooManyJumpStatements")
internal fun Reader.skipAsMuchAs(count: Long): Long {
    var skipped = 0L
    while (skipped < count) {
        val step = skip(count - skipped)
        if (step > 0) {
            skipped += step
            continue
        }
        if (read() < 0) break
        skipped++
    }
    return skipped
}

/** Something found in the shared folder, named the way the model was taught to name it. */
data class Entry(
    /** Relative to the shared folder, which is the only kind of path the model ever sees. */
    val path: String,
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mediaType: String,
)

/**
 * The folder the user shared, and the only way into it.
 *
 * Everything the file tools do goes through here, so there is one place that knows how a
 * name the model wrote becomes a document a provider will answer for. That translation is
 * the whole security boundary, and [workspaceSegments] is the half of it worth testing on a
 * host: this half cannot be, because it is questions put to a provider that only exists on
 * a device.
 *
 * Children are listed with a single cursor query per directory rather than through
 * `DocumentFile`, which issues a query per child to populate each object it returns. On a
 * folder of a few thousand entries that difference has been measured at twenty seconds
 * against under one, and twenty seconds inside a turn is not a tool, it is a hang.
 */
@Singleton
class Workspace @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val grant: WorkspaceGrant,
) {
    /** Whether there is a folder to work in at all, asked before any tool is offered. */
    val isReady: Boolean get() = grant.folder != null

    /** Whether the provider will accept a new file, which not every one of them will. */
    val acceptsNewFiles: Boolean get() = grant.state() == GrantState.READ_WRITE

    /** What to tell the model when there is nothing to work in, in its own words. */
    /**
     * The same answer as [unavailable], typed, for a tool about to hand it to the runner.
     *
     * Null when there is a folder, which reads as "carry on". A missing folder is refused
     * rather than merely failed: it is not going to appear part way through a turn, and a
     * model told "no folder has been shared" will otherwise ask the next file tool the same
     * question and spend the turn's whole budget discovering the same thing three times.
     */
    fun refusal(): ToolExecution? = unavailable().takeIf { it.isNotEmpty() }
        ?.let(ToolExecution::rejected)

    fun unavailable(): String = when (grant.state()) {
        GrantState.NONE ->
            "No folder has been shared with this app. Ask the user to choose one under Tools."
        GrantState.LOST ->
            "The shared folder is no longer reachable. It may have been disconnected, or the " +
                "permission withdrawn. Ask the user to choose it again under Tools."
        GrantState.READ_ONLY, GrantState.READ_WRITE -> ""
    }

    /**
     * Walks a path down from the shared folder, a name at a time.
     *
     * Never builds a child's identifier by joining strings onto its parent's. A provider's
     * document ids are its own business and need share nothing with each other, so the only
     * way to learn a child's id is to ask the parent for its children and match on the name
     * it gives back. That is also what makes the walk safe: at no point is there an id in
     * hand that the granted folder did not hand over.
     */
    suspend fun resolve(path: String): Entry? = withContext(Dispatchers.IO) {
        val tree = grant.folder ?: return@withContext null
        val segments = path.workspaceSegments() ?: return@withContext null

        var parentId = DocumentsContract.getTreeDocumentId(tree)
        var found: Entry? = null
        for ((index, name) in segments.withIndex()) {
            val parentPath = segments.take(index).joinToString("/")
            found = children(tree, parentId, parentPath).firstOrNull { it.name == name }
                ?: return@withContext null
            parentId = found.documentId
        }
        found
    }

    /**
     * Everything directly inside a directory, in one query.
     *
     * [under] is the path of the directory itself, carried through only so each entry can
     * report where it is in the terms the model uses. The provider never sees it.
     */
    suspend fun children(tree: Uri, parentId: String, under: String): List<Entry> =
        withContext(Dispatchers.IO) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            runCatching {
                context.contentResolver.query(childrenUri, PROJECTION, null, null, null)
                    ?.use { cursor -> cursor.entries(under) }
            }.getOrNull().orEmpty()
        }

    /** The children of a directory already resolved, which is what a search walks. */
    suspend fun children(of: Entry): List<Entry> {
        val tree = grant.folder ?: return emptyList()
        return children(tree, of.documentId, of.path)
    }

    /** The children of the shared folder itself, where every walk and every search starts. */
    suspend fun root(): List<Entry> {
        val tree = grant.folder ?: return emptyList()
        return children(tree, DocumentsContract.getTreeDocumentId(tree), "")
    }

    /** The document uri for an entry, which is the only thing a stream can be opened on. */
    fun uriFor(entry: Entry): Uri? =
        grant.folder?.let { DocumentsContract.buildDocumentUriUsingTree(it, entry.documentId) }

    /**
     * Makes a file that was not there before, and says where it went.
     *
     * Creation only, which is what keeps this tool out of the class of things that can
     * destroy work: there is no path through here that opens an existing document for
     * writing, so an interrupted call leaves a short file rather than an empty one where
     * something used to be.
     */
    suspend fun create(parent: Entry?, name: String, mediaType: String): Uri? =
        withContext(Dispatchers.IO) {
            val tree = grant.folder ?: return@withContext null
            val parentId = parent?.documentId ?: DocumentsContract.getTreeDocumentId(tree)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            runCatching {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    mediaType,
                    name,
                )
            }.getOrNull()
        }

    /**
     * Reads a window of a document without pulling the rest of it into memory.
     *
     * Bounded while the bytes are still arriving, rather than read whole and then trimmed.
     * The trimming version is what the attachment path does, and it is safe there because
     * the file came out of a picker a person tapped. Here the name came from a model, and
     * the folder it names can hold a two gigabyte video.
     */
    suspend fun readText(entry: Entry, skip: Int, take: Int): String? =
        withContext(Dispatchers.IO) {
            val uri = uriFor(entry) ?: return@withContext null
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.reader().use { reader ->
                        reader.skipAsMuchAs(skip.toLong())
                        val buffer = CharArray(take)
                        val read = reader.readAsMuchAs(buffer)
                        String(buffer, 0, read)
                    }
                }
            }.getOrNull()
        }

    /**
     * Saves text to a path that has nothing at it yet, and says what happened in a sentence.
     *
     * Phrased here rather than in the tool because every way this can fail is a fact about
     * the folder, and the tool would only be translating them back. Refusing a path that
     * already exists is the load-bearing line: without it a model that read half a long file
     * would write that half back over the whole, and report success for having done it.
     */
    // Each return is a distinct storage refusal; flattening them would obscure the boundary.
    @Suppress("ReturnCount")
    suspend fun put(path: String, text: String, replace: Boolean = false): ToolExecution {
        val segments = path.workspaceSegments()
            ?: return ToolExecution.rejected(
                "$path is not a path inside the shared folder. Try one like notes/todo.md.",
            )
        val existing = resolve(path)
        if (existing != null) {
            // Still refused by default, for the reason above. What changed is that a caller
            // can now say it means it. The danger the default guards against is a model
            // writing back the half of a file it happened to read; a caller that passes
            // replace has decided to, and the transcript records that it did. Without this
            // there is no way to fix a script and run it again, which is the loop the
            // sandbox exists for.
            if (!replace) {
                return ToolExecution.rejected(
                    "$path already exists, and this tool does not replace files unless " +
                        "asked. Call it again with replace set to true to overwrite it, or " +
                        "choose a name that is not taken.",
                )
            }
            if (existing.isDirectory) {
                return ToolExecution.rejected("$path is a folder, not a file.")
            }
            val uri = uriFor(existing)
                ?: return ToolExecution.failure("$path could not be opened for writing.")
            return if (overwrite(uri, text)) {
                ToolExecution("Replaced $path with ${text.length} characters.")
            } else {
                ToolExecution.failure("$path could not be written.")
            }
        }
        val parentPath = segments.dropLast(1).joinToString("/")
        val parent = if (parentPath.isEmpty()) {
            null
        } else {
            resolve(parentPath) ?: return ToolExecution.rejected(
                "There is no folder called $parentPath to save into.",
            )
        }
        return putInto(parent, segments.last(), text, path)
    }

    private suspend fun putInto(
        parent: Entry?,
        name: String,
        text: String,
        path: String,
    ): ToolExecution {
        val uri = create(parent, name, mediaTypeFor(name))
            ?: return ToolExecution.failure(
                "$path could not be created. The folder may not accept new files.",
            )
        return if (write(uri, text)) {
            ToolExecution("Saved ${text.length} characters to $path.")
        } else {
            // The worst outcome here, and the one worth naming: a file exists with the
            // right name and nothing in it. Reported as a failure so that a goal step
            // cannot count it as the work having been done.
            ToolExecution.failure("$path was created but nothing could be written into it.")
        }
    }

    /**
     * Replaces a document's contents, truncating whatever was there.
     *
     * Separate from [write] because the mode matters. A provider opening in its default
     * mode is not obliged to truncate, so writing eight hundred characters over two
     * thousand leaves the tail of the old file behind and reports success, which is the
     * silent half-overwrite this class refuses to allow by accident. "wt" says truncate.
     */
    suspend fun overwrite(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.writer().use { it.write(text) }
                true
            } ?: false
        }.getOrDefault(false)
    }

    /** Puts text into a document that was just created, and says whether all of it landed. */
    suspend fun write(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.writer().use { it.write(text) }
                true
            } ?: false
        }.getOrDefault(false)
    }

    private fun Cursor.entries(under: String): List<Entry> {
        val out = mutableListOf<Entry>()
        while (moveToNext() && out.size < MAX_CHILDREN) {
            val name = getString(NAME)
            val documentId = getString(ID)
            // A provider is within its rights to answer with neither, and an entry with no
            // name is one the model could never ask for again.
            if (name != null && documentId != null) out += entry(under, name, documentId)
        }
        return out
    }

    private fun Cursor.entry(under: String, name: String, documentId: String): Entry {
        val mediaType = getString(TYPE).orEmpty()
        return Entry(
            path = if (under.isEmpty()) name else "$under/$name",
            documentId = documentId,
            name = name,
            isDirectory = mediaType == DocumentsContract.Document.MIME_TYPE_DIR,
            // Size is optional in the contract, and a directory rarely reports one.
            sizeBytes = if (isNull(SIZE)) 0L else getLong(SIZE),
            mediaType = mediaType,
        )
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        /** Where each column of [PROJECTION] lands, named so the reads are not four numbers. */
        const val ID = 0
        const val TYPE = 1
        const val NAME = 2
        const val SIZE = 3

        /**
         * A cap on one directory, not on the search.
         *
         * A folder with more entries than this in it is a download directory or a photo
         * roll, and reading all of them into memory to answer one question is how a phone
         * runs out of it. The search has its own, smaller budget on top.
         */
        const val MAX_CHILDREN = 2_000
    }
}
